package com.attendance.service.insightface;

import com.attendance.dto.FaceSampleDTO;
import com.attendance.entity.Employee;
import com.attendance.entity.EmployeeFaceEmbedding;
import com.attendance.entity.EmployeeFaceSample;
import com.attendance.repository.EmployeeFaceEmbeddingRepository;
import com.attendance.repository.EmployeeFaceSampleRepository;
import com.attendance.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Manages employees' reference face samples (several photos per employee): the enrollment photo,
 * extra uploaded photos, and (phase 3) captures learned from reviews. Every sample is quality-checked
 * by {@link FaceQuality}, embedded by the InsightFace sidecar, stored in
 * {@code employee_face_samples}, and reflected in the live {@link InsightFaceGallery} after commit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaceSampleService {

    private final InsightFaceClient client;
    private final InsightFaceGallery gallery;
    private final InsightFaceSettings settings;
    private final EmployeeFaceSampleRepository sampleRepository;
    private final EmployeeFaceEmbeddingRepository legacyEmbeddingRepository;
    private final EmployeeRepository employeeRepository;

    @Value("${attendance.face-storage-dir:faces}")
    private String faceStorageDir;

    /** Result of adding one photo: accepted (with the new sample) or rejected with a reason code. */
    public record AddOutcome(boolean accepted, String code, String message, FaceSampleDTO sample) {
        static AddOutcome ok(FaceSampleDTO sample, String message) {
            return new AddOutcome(true, "OK", message, sample);
        }

        static AddOutcome reject(String code, String message) {
            return new AddOutcome(false, code, message, null);
        }
    }

    public boolean isEnabled() {
        return settings.isEnabled() && client.isEnabled();
    }

    // ---- Queries --------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<FaceSampleDTO> listSamples(Long employeeDbId) {
        return sampleRepository.findByEmployeeIdOrderByCreatedAtAsc(employeeDbId).stream()
                .map(FaceSampleService::toDTO)
                .collect(Collectors.toList());
    }

    // ---- Uploaded samples -----------------------------------------------------------------------

    /**
     * Add one extra photo for an employee. The photo must contain exactly one good-quality face, must
     * not be a near-duplicate of an existing sample, and (unless {@code force}) must not look more
     * like a different employee — a guard against uploading a photo to the wrong person.
     */
    @Transactional
    public AddOutcome addUploadedSample(Long employeeDbId, byte[] rawPhoto, boolean force, String username) {
        if (!isEnabled()) {
            return AddOutcome.reject("DISABLED", "InsightFace is disabled (set insightface.enabled=true)");
        }
        Employee employee = employeeRepository.findById(employeeDbId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));

        byte[] jpeg;
        try {
            jpeg = JpegUtil.toJpeg(rawPhoto, settings.getMaxImageSide());
        } catch (IllegalArgumentException e) {
            return AddOutcome.reject("INVALID_IMAGE", e.getMessage());
        }

        List<EmployeeFaceSample> existing = sampleRepository.findByEmployeeIdOrderByCreatedAtAsc(employeeDbId);
        if (existing.size() >= settings.getMaxSamplesPerEmployee()) {
            return AddOutcome.reject("LIMIT_REACHED", "This employee already has " + existing.size()
                    + " photos (limit " + settings.getMaxSamplesPerEmployee() + "). Delete one first.");
        }

        List<DetectedFace> faces = client.embed(jpeg);
        if (faces.isEmpty() && !client.isAvailable()) {
            return AddOutcome.reject("SERVICE_UNAVAILABLE", "The InsightFace service is not reachable");
        }
        FaceQuality.Assessment assessment = FaceQuality.assessEnrollment(faces, settings.samplePolicy());
        if (!assessment.ok()) {
            return AddOutcome.reject(assessment.issue().name(), assessment.detail());
        }
        DetectedFace face = FaceQuality.primaryFace(faces, settings.getSampleMinDetScore());
        float[] vector = EmbeddingCodec.normalize(face.embedding());

        List<FaceMatcher.Sample> own = toSamples(existing);
        double duplicate = FaceMatcher.maxSimilarity(vector, own);
        if (duplicate >= settings.getDuplicateSimilarity()) {
            return AddOutcome.reject("DUPLICATE", String.format(
                    "Almost identical to a photo this employee already has (similarity %.2f). "
                            + "Use a different angle, lighting or expression.", duplicate));
        }

        if (settings.isIdentityCheck() && !force) {
            String conflict = identityConflict(employeeDbId, vector, own);
            if (conflict != null) {
                return AddOutcome.reject("LOOKS_LIKE_OTHER", conflict);
            }
        }

        String imageUrl;
        try {
            imageUrl = writeSampleFile(employeeDbId, jpeg);
        } catch (IOException e) {
            log.warn("Could not store face sample for employee {}: {}", employee.getEmployeeId(), e.getMessage());
            return AddOutcome.reject("STORAGE_ERROR", "Could not save the photo: " + e.getMessage());
        }

        EmployeeFaceSample saved = sampleRepository.save(buildSample(employeeDbId, EmployeeFaceSample.Source.UPLOADED,
                imageUrl, vector, face, assessment.score(), username));
        refreshAfterCommit(employeeDbId);
        log.info("Added face sample #{} for employee {} (quality {})", saved.getId(), employee.getEmployeeId(),
                String.format("%.2f", assessment.score()));
        return AddOutcome.ok(toDTO(saved), "Photo added");
    }

    /**
     * When the photo matches another employee at least as confidently as an automatic match, and
     * better than this employee's own samples, describe the conflict; otherwise null.
     */
    private String identityConflict(Long employeeDbId, float[] vector, List<FaceMatcher.Sample> own) {
        Map<Long, List<FaceMatcher.Sample>> others = gallery.snapshot();
        others.remove(employeeDbId);
        List<FaceMatcher.Candidate> ranked = FaceMatcher.rank(vector, others, settings.aggregationMode(), 1);
        if (ranked.isEmpty()) {
            return null;
        }
        FaceMatcher.Candidate other = ranked.get(0);
        double ownScore = FaceMatcher.scoreEmployee(employeeDbId, vector, own, settings.aggregationMode())
                .map(FaceMatcher.Candidate::score).orElse(0.0);
        if (other.score() < settings.getHighThreshold() || other.score() <= ownScore) {
            return null;
        }
        String who = employeeRepository.findById(other.employeeDbId())
                .map(e -> e.getFullName() + " (" + e.getEmployeeId() + ")")
                .orElse("employee #" + other.employeeDbId());
        return String.format("This photo looks like %s (similarity %.2f) more than this employee (%.2f). "
                + "Check that it is the right person, or upload again with \"skip similarity check\".",
                who, other.score(), ownScore);
    }

    /** Delete one sample. The enrollment (camera) photo cannot be deleted here. */
    @Transactional
    public void deleteSample(Long employeeDbId, Long sampleId) {
        EmployeeFaceSample sample = sampleRepository.findById(sampleId)
                .filter(s -> s.getEmployeeId().equals(employeeDbId))
                .orElseThrow(() -> new IllegalArgumentException("Face sample not found"));
        if (sample.getSource() == EmployeeFaceSample.Source.ENROLLED) {
            throw new IllegalArgumentException(
                    "This is the enrollment photo that is also on the cameras. Change it with Edit employee.");
        }
        sampleRepository.delete(sample);
        deleteSampleFileQuietly(sample.getImageUrl());
        refreshAfterCommit(employeeDbId);
    }

    // ---- Learned samples (phase 3) --------------------------------------------------------------

    /**
     * Store a camera capture as a learned sample (REVIEW_CONFIRMED or AUTO_LEARNED). The caller has
     * already checked quality and novelty. Keeps at most {@code max-learned-per-employee} learned
     * samples and {@code max-per-employee} samples in total by replacing the oldest learned one;
     * uploaded and enrollment photos are never replaced. Returns the new sample, or null when there
     * was no room.
     */
    @Transactional
    public FaceSampleDTO addLearnedSample(Long employeeDbId, byte[] jpeg, DetectedFace face, float[] vector,
                                          double quality, EmployeeFaceSample.Source source, Long captureId,
                                          Double matchScore, String username) {
        if (!source.isLearned()) {
            throw new IllegalArgumentException("Not a learned source: " + source);
        }
        if (settings.getMaxLearnedPerEmployee() <= 0) {
            return null;
        }
        if (captureId != null && sampleRepository.existsBySourceCaptureId(captureId)) {
            return null; // this capture was learned already
        }
        List<EmployeeFaceSample> learned = sampleRepository.findByEmployeeIdAndSourceInOrderByCreatedAtAsc(
                employeeDbId, List.of(EmployeeFaceSample.Source.REVIEW_CONFIRMED, EmployeeFaceSample.Source.AUTO_LEARNED));
        long total = sampleRepository.countByEmployeeId(employeeDbId);
        // How many of the oldest learned samples must go to make room for one more.
        int removeCount = 0;
        while (removeCount < learned.size()
                && (learned.size() - removeCount >= settings.getMaxLearnedPerEmployee()
                || total - removeCount >= settings.getMaxSamplesPerEmployee())) {
            removeCount++;
        }
        if (total - removeCount >= settings.getMaxSamplesPerEmployee()) {
            return null; // only uploaded/enrollment photos left and the employee is at the limit
        }
        String imageUrl;
        try {
            imageUrl = writeSampleFile(employeeDbId, JpegUtil.toJpeg(jpeg, settings.getMaxImageSide()));
        } catch (Exception e) {
            log.warn("Could not store learned face sample for employee #{}: {}", employeeDbId, e.getMessage());
            return null;
        }
        for (EmployeeFaceSample oldest : learned.subList(0, removeCount)) {
            sampleRepository.delete(oldest);
            String oldUrl = oldest.getImageUrl();
            afterCommit(() -> deleteSampleFileQuietly(oldUrl));
            log.info("Replaced oldest learned face sample #{} of employee #{}", oldest.getId(), employeeDbId);
        }
        EmployeeFaceSample row = buildSample(employeeDbId, source, imageUrl, vector, face, quality, username);
        row.setSourceCaptureId(captureId);
        row.setMatchScore(matchScore);
        EmployeeFaceSample saved = sampleRepository.save(row);
        refreshAfterCommit(employeeDbId);
        return toDTO(saved);
    }

    /** When the employee last got an AUTO_LEARNED sample, or null. */
    @Transactional(readOnly = true)
    public LocalDateTime lastAutoLearnedAt(Long employeeDbId) {
        return sampleRepository.findTopByEmployeeIdAndSourceOrderByCreatedAtDesc(
                        employeeDbId, EmployeeFaceSample.Source.AUTO_LEARNED)
                .map(EmployeeFaceSample::getCreatedAt).orElse(null);
    }

    /** Read a stored image by its public {@code /faces/...} URL (never outside the storage dir). */
    public byte[] readStoredImage(String imageUrl) throws IOException {
        if (imageUrl == null || !imageUrl.startsWith("/faces/")) {
            throw new IOException("Not a stored face image: " + imageUrl);
        }
        Path base = Paths.get(faceStorageDir).toAbsolutePath().normalize();
        Path file = base.resolve(imageUrl.substring("/faces/".length())).normalize();
        if (!file.startsWith(base)) {
            throw new IOException("Image path escapes the face storage directory: " + imageUrl);
        }
        return Files.readAllBytes(file);
    }

    // ---- Enrollment (primary) sample ------------------------------------------------------------

    /**
     * (Re)build the employee's ENROLLED sample from their enrollment photo. The enrollment photo is
     * already on the cameras, so it is stored even when it fails the strict sample quality rules
     * (a warning is logged); it is only skipped when no face is found. Returns whether it was stored.
     */
    @Transactional
    public boolean replaceEnrolledSample(Employee employee, byte[] jpeg) {
        if (!isEnabled() || employee == null || employee.getId() == null || jpeg == null || jpeg.length == 0) {
            return false;
        }
        List<DetectedFace> faces = client.embed(jpeg);
        DetectedFace face = FaceQuality.primaryFace(faces, settings.getSampleMinDetScore());
        if (face == null) {
            log.info("InsightFace found no face in the enrolled photo of employee {}", employee.getEmployeeId());
            return false;
        }
        FaceQuality.Assessment assessment = FaceQuality.assessEnrollment(faces, settings.samplePolicy());
        if (!assessment.ok()) {
            log.warn("Enrollment photo of employee {} is below the recommended quality: {}. "
                    + "Recognition may be weaker; consider a better photo or adding extra photos.",
                    employee.getEmployeeId(), assessment.detail());
        }
        float[] vector = EmbeddingCodec.normalize(face.embedding());

        sampleRepository.deleteAll(
                sampleRepository.findByEmployeeIdAndSource(employee.getId(), EmployeeFaceSample.Source.ENROLLED));
        String imageUrl = employee.getFaceImageUrl() != null && !employee.getFaceImageUrl().isBlank()
                ? employee.getFaceImageUrl()
                : "/faces/enrolled/employee_" + employee.getId() + ".jpg";
        sampleRepository.save(buildSample(employee.getId(), EmployeeFaceSample.Source.ENROLLED, imageUrl, vector,
                face, assessment.score(), null));
        refreshAfterCommit(employee.getId());
        log.info("Stored enrollment face sample for employee {} (dim={}, det={}, quality={})",
                employee.getEmployeeId(), vector.length, String.format("%.2f", face.detScore()),
                String.format("%.2f", assessment.score()));
        return true;
    }

    /** Remove every sample (rows and files) of an employee — used when their face is removed. */
    @Transactional
    public void removeAllSamples(Long employeeDbId) {
        if (employeeDbId == null) {
            return;
        }
        sampleRepository.deleteByEmployeeId(employeeDbId);
        legacyEmbeddingRepository.deleteByEmployeeId(employeeDbId);
        deleteSampleDirectoryQuietly(employeeDbId);
        afterCommit(() -> gallery.evict(employeeDbId));
    }

    /** Whether the employee already has an ENROLLED sample. */
    @Transactional(readOnly = true)
    public boolean hasEnrolledSample(Long employeeDbId) {
        return !sampleRepository.findByEmployeeIdAndSource(employeeDbId, EmployeeFaceSample.Source.ENROLLED).isEmpty();
    }

    /**
     * One-time migration: copy embeddings from the old one-per-employee table
     * ({@code employee_face_embeddings}) into ENROLLED samples, so existing installations keep
     * working without calling the sidecar again. Idempotent. Returns the number migrated.
     */
    @Transactional
    public int migrateLegacyEmbeddings() {
        int migrated = 0;
        for (EmployeeFaceEmbedding legacy : legacyEmbeddingRepository.findAll()) {
            Long employeeDbId = legacy.getEmployeeId();
            if (employeeDbId == null || legacy.getEmbedding() == null || sampleRepository.existsByEmployeeId(employeeDbId)) {
                continue;
            }
            float[] vector = EmbeddingCodec.normalize(EmbeddingCodec.toFloats(legacy.getEmbedding()));
            if (vector.length == 0) {
                continue;
            }
            String imageUrl = employeeRepository.findById(employeeDbId)
                    .map(Employee::getFaceImageUrl)
                    .filter(u -> u != null && !u.isBlank())
                    .orElse("/faces/enrolled/employee_" + employeeDbId + ".jpg");
            sampleRepository.save(EmployeeFaceSample.builder()
                    .employeeId(employeeDbId)
                    .source(EmployeeFaceSample.Source.ENROLLED)
                    .imageUrl(imageUrl)
                    .embedding(EmbeddingCodec.toBytes(vector))
                    .dim(vector.length)
                    .model(legacy.getModel() != null ? legacy.getModel() : settings.getModel())
                    .detScore(legacy.getDetScore())
                    .build());
            migrated++;
        }
        if (migrated > 0) {
            log.info("Migrated {} legacy InsightFace embedding(s) into face samples", migrated);
        }
        return migrated;
    }

    // ---- Helpers --------------------------------------------------------------------------------

    EmployeeFaceSample buildSample(Long employeeDbId, EmployeeFaceSample.Source source, String imageUrl,
                                   float[] vector, DetectedFace face, double quality, String username) {
        return EmployeeFaceSample.builder()
                .employeeId(employeeDbId)
                .source(source)
                .imageUrl(imageUrl)
                .embedding(EmbeddingCodec.toBytes(vector))
                .dim(vector.length)
                .model(settings.getModel())
                .detScore(face.detScore())
                .qualityScore(quality)
                .faceSize(face.faceSize())
                .yaw(face.yaw())
                .pitch(face.pitch())
                .blur(face.blur())
                .createdBy(username)
                .build();
    }

    static List<FaceMatcher.Sample> toSamples(List<EmployeeFaceSample> rows) {
        return rows.stream()
                .filter(r -> r.getEmbedding() != null)
                .map(r -> new FaceMatcher.Sample(r.getId(), EmbeddingCodec.toFloats(r.getEmbedding())))
                .collect(Collectors.toList());
    }

    /** Write a sample JPEG under {@code faces/samples/employee_{id}/} and return its public URL. */
    String writeSampleFile(Long employeeDbId, byte[] jpeg) throws IOException {
        Path dir = Paths.get(faceStorageDir, "samples", "employee_" + employeeDbId);
        Files.createDirectories(dir);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        String filename = "sample_" + ts + "_" + ThreadLocalRandom.current().nextInt(10000) + ".jpg";
        Files.write(dir.resolve(filename), jpeg);
        return "/faces/samples/employee_" + employeeDbId + "/" + filename;
    }

    /** Delete a sample file, but only files under {@code faces/samples/} (never the enrollment photo). */
    void deleteSampleFileQuietly(String imageUrl) {
        if (imageUrl == null || !imageUrl.startsWith("/faces/samples/")) {
            return;
        }
        try {
            Path base = Paths.get(faceStorageDir, "samples").toAbsolutePath().normalize();
            Path file = Paths.get(faceStorageDir).toAbsolutePath().normalize()
                    .resolve(imageUrl.substring("/faces/".length())).normalize();
            if (file.startsWith(base)) {
                Files.deleteIfExists(file);
            }
        } catch (Exception e) {
            log.warn("Could not delete face sample file {}: {}", imageUrl, e.getMessage());
        }
    }

    private void deleteSampleDirectoryQuietly(Long employeeDbId) {
        Path dir = Paths.get(faceStorageDir, "samples", "employee_" + employeeDbId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                Files.deleteIfExists(f);
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("Could not delete face samples of employee #{}: {}", employeeDbId, e.getMessage());
        }
    }

    /** Refresh the employee in the live gallery once the current transaction has committed. */
    void refreshAfterCommit(Long employeeDbId) {
        afterCommit(() -> gallery.refreshEmployee(employeeDbId));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    static FaceSampleDTO toDTO(EmployeeFaceSample s) {
        return FaceSampleDTO.builder()
                .id(s.getId())
                .employeeId(s.getEmployeeId())
                .source(s.getSource() != null ? s.getSource().name() : null)
                .primary(s.getSource() == EmployeeFaceSample.Source.ENROLLED)
                .imageUrl(s.getImageUrl())
                .qualityScore(s.getQualityScore())
                .detScore(s.getDetScore())
                .faceSize(s.getFaceSize())
                .yaw(s.getYaw())
                .pitch(s.getPitch())
                .blur(s.getBlur())
                .matchScore(s.getMatchScore())
                .model(s.getModel())
                .createdBy(s.getCreatedBy())
                .createdAt(s.getCreatedAt() != null ? s.getCreatedAt().toString() : null)
                .build();
    }
}
