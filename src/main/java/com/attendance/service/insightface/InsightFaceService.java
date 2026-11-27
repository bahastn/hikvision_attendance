package com.attendance.service.insightface;

import com.attendance.entity.Employee;
import com.attendance.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * @param band          HIGH / UNCERTAIN / NO_MATCH
     * @param candidate     best employee (null unless HIGH/UNCERTAIN)
     * @param score         best employee's score
     * @param runnerUpScore second-best employee's score (0 when none)
     * @param reason        why: CONFIDENT, BELOW_HIGH, AMBIGUOUS, BELOW_LOW, EMPTY_GALLERY,
     *                      NO_FACE, LOW_QUALITY:&lt;issue&gt;, DISABLED
     * @param face          the probe face that was matched (null when none was usable)
     * @param probe         the probe's normalized embedding (null when none)
     */
    public record RecognitionResult(FaceMatcher.Band band, Employee candidate, double score,
                                    double runnerUpScore, String reason, DetectedFace face, float[] probe) {
        public static RecognitionResult noMatch() {
            return noMatch("NO_FACE", null, null);
        }

        public static RecognitionResult noMatch(String reason, DetectedFace face, float[] probe) {
            return new RecognitionResult(FaceMatcher.Band.NO_MATCH, null, 0.0, 0.0, reason, face, probe);
        }

        /** Gap between the best and second-best employee. */
        public double margin() {
            return score - runnerUpScore;
        }
    }

    /**
     * Recognize a captured face against the gallery. Never throws. Not wrapped in a transaction so
     * the (potentially slow) sidecar call does not hold a database connection.
     */
    public RecognitionResult recognize(byte[] jpeg) {
        if (!isEnabled() || jpeg == null || jpeg.length == 0) {
            return RecognitionResult.noMatch("DISABLED", null, null);
        }
        List<DetectedFace> faces = client.embed(jpeg);
        DetectedFace face = faces.isEmpty() ? null : faces.get(0);
        if (face == null || face.detScore() < settings.getProbeMinDetScore()) {
            // The crop is probably not a (usable) face; don't try to match it.
            return RecognitionResult.noMatch("NO_FACE", face, null);
        }
        float[] probe = EmbeddingCodec.normalize(face.embedding());
        return match(probe, face);
    }

    /** Rank a probe embedding against the gallery and decide the band. */
    RecognitionResult match(float[] probe, DetectedFace face) {
        Map<Long, List<FaceMatcher.Sample>> snapshot = gallery.snapshot();
        List<FaceMatcher.Candidate> ranked = FaceMatcher.rank(probe, snapshot, FaceMatcher.Aggregation.MAX, 2);
        if (ranked.isEmpty()) {
            return RecognitionResult.noMatch("EMPTY_GALLERY", face, probe);
        }
        FaceMatcher.Candidate best = ranked.get(0);
        double runnerUp = ranked.size() > 1 ? ranked.get(1).score() : 0.0;
        FaceMatcher.Band band = FaceMatcher.band(best.score(), settings.getLowThreshold(), settings.getHighThreshold());
        if (band == FaceMatcher.Band.NO_MATCH) {
            return new RecognitionResult(band, null, best.score(), runnerUp, "BELOW_LOW", face, probe);
        }
        Optional<Employee> employee = employeeRepository.findById(best.employeeDbId())
                .filter(e -> Boolean.TRUE.equals(e.getIsActive()));
        if (employee.isEmpty()) {
            // Gallery is momentarily stale (employee deactivated/removed) — treat as no match.
            return RecognitionResult.noMatch("EMPTY_GALLERY", face, probe);
        }
        return new RecognitionResult(band, employee.get(), best.score(), runnerUp,
                band == FaceMatcher.Band.HIGH ? "CONFIDENT" : "BELOW_HIGH", face, probe);
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
