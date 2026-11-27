package com.attendance.service.insightface;

import com.attendance.entity.Employee;
import com.attendance.entity.EmployeeFaceEmbedding;
import com.attendance.repository.EmployeeFaceEmbeddingRepository;
import com.attendance.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates the secondary (fallback) face-recognition layer: image → embedding (via the
 * {@link InsightFaceClient} sidecar) → best match in the {@link InsightFaceGallery} → confidence
 * {@link FaceMatcher.Band}. Also generates and stores the embedding for an employee's enrolled
 * photo.
 *
 * <p>Every public method degrades gracefully: when the feature is disabled or the sidecar is
 * unavailable, {@link #recognize} returns {@link FaceMatcher.Band#NO_MATCH} and
 * {@link #embedAndStore} returns {@code false} — the caller then behaves exactly as before.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InsightFaceService {

    private final InsightFaceClient client;
    private final InsightFaceGallery gallery;
    private final EmployeeFaceEmbeddingRepository embeddingRepository;
    private final EmployeeRepository employeeRepository;

    @Value("${insightface.enabled:false}")
    private boolean enabled;

    @Value("${insightface.model:buffalo_l}")
    private String model;

    @Value("${insightface.high-threshold:0.60}")
    private double highThreshold;

    @Value("${insightface.low-threshold:0.40}")
    private double lowThreshold;

    @Value("${insightface.min-det-score:0.50}")
    private double minDetScore;

    public boolean isEnabled() {
        return enabled && client.isEnabled();
    }

    /** Result of a fallback recognition: the band, the candidate (null unless HIGH/UNCERTAIN) and the score. */
    public record RecognitionResult(FaceMatcher.Band band, Employee candidate, double score) {
        public static RecognitionResult noMatch() {
            return new RecognitionResult(FaceMatcher.Band.NO_MATCH, null, 0.0);
        }
    }

    /**
     * Recognize a captured face against the enrolled gallery. Never throws. Not wrapped in a
     * transaction so the (potentially slow) sidecar call does not hold a database connection; the
     * single employee lookup at the end opens its own.
     */
    public RecognitionResult recognize(byte[] jpeg) {
        if (!isEnabled() || jpeg == null || jpeg.length == 0) {
            return RecognitionResult.noMatch();
        }
        Optional<InsightFaceClient.FaceEmbedding> best = client.embedBestFace(jpeg);
        if (best.isEmpty()) {
            return RecognitionResult.noMatch();
        }
        InsightFaceClient.FaceEmbedding face = best.get();
        if (face.detScore() < minDetScore) {
            // The crop is probably not a (usable) face; don't try to match it.
            return RecognitionResult.noMatch();
        }

        float[] probe = EmbeddingCodec.normalize(face.embedding());
        Map<Long, float[]> snapshot = gallery.snapshot();
        Optional<FaceMatcher.Candidate> candidate = FaceMatcher.bestMatch(probe, snapshot);
        if (candidate.isEmpty()) {
            return RecognitionResult.noMatch();
        }

        double score = candidate.get().score();
        FaceMatcher.Band band = FaceMatcher.band(score, lowThreshold, highThreshold);
        if (band == FaceMatcher.Band.NO_MATCH) {
            return RecognitionResult.noMatch();
        }

        Employee employee = employeeRepository.findById(candidate.get().employeeDbId())
                .filter(e -> Boolean.TRUE.equals(e.getIsActive()))
                .orElse(null);
        if (employee == null) {
            // Gallery is momentarily stale (employee deactivated/removed) — treat as no match.
            return RecognitionResult.noMatch();
        }
        return new RecognitionResult(band, employee, score);
    }

    /**
     * Generate the embedding for an employee's photo and store/replace it, updating the live gallery.
     * Best-effort: returns {@code false} (without throwing) when disabled, the sidecar is down, or no
     * face is found. Called from the enrollment hook and the backfill runner.
     */
    @Transactional
    public boolean embedAndStore(Employee employee, byte[] jpeg) {
        if (!isEnabled() || employee == null || employee.getId() == null || jpeg == null || jpeg.length == 0) {
            return false;
        }
        Optional<InsightFaceClient.FaceEmbedding> best = client.embedBestFace(jpeg);
        if (best.isEmpty()) {
            log.info("InsightFace found no face in the enrolled photo of employee {}", employee.getEmployeeId());
            return false;
        }
        InsightFaceClient.FaceEmbedding face = best.get();
        float[] normalized = EmbeddingCodec.normalize(face.embedding());

        EmployeeFaceEmbedding row = embeddingRepository.findByEmployeeId(employee.getId())
                .orElseGet(() -> EmployeeFaceEmbedding.builder().employeeId(employee.getId()).build());
        row.setEmbedding(EmbeddingCodec.toBytes(normalized));
        row.setDim(normalized.length);
        row.setModel(model);
        row.setDetScore(face.detScore());
        embeddingRepository.save(row);

        gallery.put(employee, normalized);
        log.info("Stored InsightFace embedding for employee {} (dim={}, det={})",
                employee.getEmployeeId(), normalized.length, String.format("%.2f", face.detScore()));
        return true;
    }

    /** Delete an employee's stored embedding and evict it from the gallery. */
    @Transactional
    public void removeEmbedding(Long employeeDbId) {
        if (employeeDbId == null) {
            return;
        }
        try {
            embeddingRepository.deleteByEmployeeId(employeeDbId);
        } catch (Exception e) {
            log.warn("Could not delete InsightFace embedding for employee #{}: {}", employeeDbId, e.getMessage());
        }
        gallery.evict(employeeDbId);
    }
}
