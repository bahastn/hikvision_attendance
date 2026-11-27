package com.attendance.service.insightface;

import com.attendance.entity.Employee;
import com.attendance.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Orchestrates the InsightFace recognition layer: image → embedding (via the {@link InsightFaceClient}
 * sidecar) → ranking against every employee's samples in the {@link InsightFaceGallery} → confidence
 * {@link FaceMatcher.Band}. Sample management lives in {@link FaceSampleService}.
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
    private final InsightFaceSettings settings;
    private final FaceSampleService sampleService;
    private final EmployeeRepository employeeRepository;

    public boolean isEnabled() {
        return settings.isEnabled() && client.isEnabled();
    }

    /**
     * Result of a recognition.
     *
     * @param band             HIGH / UNCERTAIN / NO_MATCH
     * @param candidate        best employee (null unless HIGH/UNCERTAIN)
     * @param score            best employee's score
     * @param runnerUpScore    second-best employee's score (0 when none)
     * @param runnerUpDbId     second-best employee's database id (null when none)
     * @param reason           why: CONFIDENT, BELOW_HIGH, AMBIGUOUS, BELOW_LOW, EMPTY_GALLERY,
     *                         NO_FACE, LOW_QUALITY:&lt;issue&gt;, DISABLED
     * @param face             the probe face that was matched (null when none was usable)
     * @param probe            the probe's normalized embedding (null when none)
     * @param picture          the JPEG that was analysed (the event picture or its fallback)
     */
    public record RecognitionResult(FaceMatcher.Band band, Employee candidate, double score,
                                    double runnerUpScore, Long runnerUpDbId, String reason,
                                    DetectedFace face, float[] probe, byte[] picture) {
        public static RecognitionResult noMatch() {
            return noMatch("NO_FACE", null, null);
        }

        public static RecognitionResult noMatch(String reason, DetectedFace face, float[] probe) {
            return new RecognitionResult(FaceMatcher.Band.NO_MATCH, null, 0.0, 0.0, null, reason, face, probe, null);
        }

        /** Gap between the best and second-best employee. */
        public double margin() {
            return score - runnerUpScore;
        }

        RecognitionResult withPicture(byte[] jpeg) {
            return new RecognitionResult(band, candidate, score, runnerUpScore, runnerUpDbId, reason, face, probe, jpeg);
        }

        /** True when no usable face was found (as opposed to a face that matched nobody). */
        public boolean unusablePicture() {
            return reason != null && (reason.equals("NO_FACE") || reason.startsWith("LOW_QUALITY"));
        }
    }

    /**
     * Recognize a captured face against the gallery. Never throws. Not wrapped in a transaction so
     * the (potentially slow) sidecar call does not hold a database connection.
     *
     * <p>Steps: pick the main face (largest confident face, so a wide scene picture works too) →
     * probe quality gate (too small / blurred / turned away faces are not matched, because their
     * scores are unreliable) → rank employees by their samples → decide with the margin rule.
     */
    public RecognitionResult recognize(byte[] jpeg) {
        if (!isEnabled() || jpeg == null || jpeg.length == 0) {
            return RecognitionResult.noMatch("DISABLED", null, null);
        }
        List<DetectedFace> faces = client.embed(jpeg);
        DetectedFace face = FaceQuality.primaryFace(faces, settings.getProbeMinDetScore());
        if (face == null) {
            return RecognitionResult.noMatch("NO_FACE", null, null);
        }
        FaceQuality.Assessment quality = FaceQuality.assess(face, settings.probePolicy());
        if (!quality.ok()) {
            log.debug("Probe face rejected: {}", quality.detail());
            return RecognitionResult.noMatch("LOW_QUALITY:" + quality.issue().name(), face, null);
        }
        float[] probe = EmbeddingCodec.normalize(face.embedding());
        return match(probe, face).withPicture(jpeg);
    }

    /**
     * Recognize the event picture; when it has no usable face, try the event's second picture
     * (wider scene / full resolution), if the supplier returns one.
     */
    public RecognitionResult recognize(byte[] jpeg, Supplier<byte[]> fallbackPicture) {
        RecognitionResult first = recognize(jpeg);
        if (!first.unusablePicture() || fallbackPicture == null) {
            return first;
        }
        byte[] second = fallbackPicture.get();
        if (second == null || second.length == 0) {
            return first;
        }
        RecognitionResult retry = recognize(second);
        log.debug("Retried recognition on the second event picture: {} -> {}", first.reason(), retry.reason());
        return retry.unusablePicture() ? first : retry;
    }

    /** Rank a probe embedding against the gallery and decide the band (with the margin rule). */
    RecognitionResult match(float[] probe, DetectedFace face) {
        Map<Long, List<FaceMatcher.Sample>> snapshot = gallery.snapshot();
        List<FaceMatcher.Candidate> ranked = FaceMatcher.rank(probe, snapshot, settings.aggregationMode(), 2);
        FaceMatcher.Decision decision = FaceMatcher.decide(ranked, settings.getLowThreshold(),
                settings.getHighThreshold(), settings.getMinMargin());
        if (decision.best() == null) {
            return RecognitionResult.noMatch("EMPTY_GALLERY", face, probe);
        }
        double runnerUpScore = decision.runnerUp() != null ? decision.runnerUp().score() : 0.0;
        Long runnerUpId = decision.runnerUp() != null ? decision.runnerUp().employeeDbId() : null;
        if (decision.band() == FaceMatcher.Band.NO_MATCH) {
            return new RecognitionResult(FaceMatcher.Band.NO_MATCH, null, decision.best().score(), runnerUpScore,
                    runnerUpId, decision.reason().name(), face, probe, null);
        }
        Optional<Employee> employee = employeeRepository.findById(decision.best().employeeDbId())
                .filter(e -> Boolean.TRUE.equals(e.getIsActive()));
        if (employee.isEmpty()) {
            // Gallery is momentarily stale (employee deactivated/removed) — treat as no match.
            return RecognitionResult.noMatch("EMPTY_GALLERY", face, probe);
        }
        return new RecognitionResult(decision.band(), employee.get(), decision.best().score(), runnerUpScore,
                runnerUpId, decision.reason().name(), face, probe, null);
    }

    /**
     * Score one specific employee against a probe (their samples only), or empty when they have no
     * samples in the gallery.
     */
    public Optional<Double> scoreFor(Long employeeDbId, float[] probe) {
        return FaceMatcher.scoreEmployee(employeeDbId, probe, gallery.samplesOf(employeeDbId),
                settings.aggregationMode()).map(FaceMatcher.Candidate::score);
    }

    /** Short human-readable explanation of an uncertain result, for the review queue. */
    public String describeUncertain(RecognitionResult result) {
        if (result == null) {
            return null;
        }
        if ("AMBIGUOUS".equals(result.reason()) && result.runnerUpDbId() != null) {
            String other = employeeRepository.findById(result.runnerUpDbId())
                    .map(e -> e.getFullName() + " (" + e.getEmployeeId() + ")")
                    .orElse("another employee");
            return String.format("Too close to %s: %.2f vs %.2f", other, result.score(), result.runnerUpScore());
        }
        return String.format("Score %.2f is below the automatic threshold %.2f",
                result.score(), settings.getHighThreshold());
    }

    /**
     * (Re)build the employee's enrollment sample from their enrollment photo. Called from the
     * enrollment hook and the backfill. Best-effort; returns {@code false} without throwing.
     */
    public boolean embedAndStore(Employee employee, byte[] jpeg) {
        try {
            return sampleService.replaceEnrolledSample(employee, jpeg);
        } catch (Exception e) {
            log.warn("Could not store the enrollment face sample of employee {}: {}",
                    employee != null ? employee.getEmployeeId() : null, e.getMessage());
            return false;
        }
    }

    /** Delete all of an employee's samples and evict them from the gallery. */
    public void removeEmbedding(Long employeeDbId) {
        if (employeeDbId == null) {
            return;
        }
        try {
            sampleService.removeAllSamples(employeeDbId);
        } catch (Exception e) {
            log.warn("Could not delete face samples of employee #{}: {}", employeeDbId, e.getMessage());
        }
        gallery.evict(employeeDbId);
    }
}
