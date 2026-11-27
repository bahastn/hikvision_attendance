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
        Probe probe = probe(jpeg);
        if (!probe.usable()) {
            return RecognitionResult.noMatch(probe.reason(), probe.face(), null);
        }
        return match(probe.vector(), probe.face()).withPicture(jpeg);
    }

    /** A captured face prepared for matching: usable (with its vector) or the reason it is not. */
    record Probe(DetectedFace face, float[] vector, String reason, byte[] picture) {
        boolean usable() {
            return vector != null;
        }
    }

    /** Detect the main face in a picture and apply the probe quality gate. */
    Probe probe(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) {
            return new Probe(null, null, "NO_FACE", jpeg);
        }
        List<DetectedFace> faces = client.embed(jpeg);
        DetectedFace face = FaceQuality.primaryFace(faces, settings.getProbeMinDetScore());
        if (face == null) {
            return new Probe(null, null, "NO_FACE", jpeg);
        }
        FaceQuality.Assessment quality = FaceQuality.assess(face, settings.probePolicy());
        if (!quality.ok()) {
            log.debug("Probe face rejected: {}", quality.detail());
            return new Probe(face, null, "LOW_QUALITY:" + quality.issue().name(), jpeg);
        }
        return new Probe(face, EmbeddingCodec.normalize(face.embedding()), "OK", jpeg);
    }

    /**
     * Result of checking a camera match with InsightFace (phase 5).
     *
     * @param outcome          AGREE / WEAK / CONFLICT / NOT_CHECKED
     * @param reason           for NOT_CHECKED: DISABLED, NO_SAMPLES, NO_FACE, LOW_QUALITY:..., STORED_PHOTO
     * @param cameraScore      InsightFace score against the camera's employee
     * @param other            for CONFLICT (and informative otherwise): the best other employee
     * @param otherScore       that employee's score
     * @param recognition      when InsightFace itself would confidently pick the camera's employee:
     *                         a HIGH result usable for auto-learning; otherwise null
     */
    public record VerificationResult(CameraVerification.Outcome outcome, String reason, double cameraScore,
                                     Employee other, double otherScore, RecognitionResult recognition) {
        static VerificationResult notChecked(String reason) {
            return new VerificationResult(CameraVerification.Outcome.NOT_CHECKED, reason, 0.0, null, 0.0, null);
        }
    }

    /**
     * Second opinion on a match the camera made: compare the captured face with the camera's
     * employee's photos and with everyone else. Never throws; anything that prevents a real check
     * gives NOT_CHECKED so the camera's decision stands.
     */
    public VerificationResult verifyCameraMatch(Employee cameraEmployee, byte[] jpeg, Supplier<byte[]> fallbackPicture) {
        try {
            return doVerify(cameraEmployee, jpeg, fallbackPicture);
        } catch (Exception e) {
            log.warn("InsightFace verification failed: {}", e.getMessage());
            return VerificationResult.notChecked("ERROR");
        }
    }

    private VerificationResult doVerify(Employee cameraEmployee, byte[] jpeg, Supplier<byte[]> fallbackPicture) {
        if (!isEnabled() || !settings.isVerifyCameraMatches() || cameraEmployee == null || cameraEmployee.getId() == null) {
            return VerificationResult.notChecked("DISABLED");
        }
        List<FaceMatcher.Sample> own = gallery.samplesOf(cameraEmployee.getId());
        if (own.isEmpty()) {
            return VerificationResult.notChecked("NO_SAMPLES");
        }
        Probe probe = probe(jpeg);
        boolean stored = probe.usable()
                && FaceMatcher.maxSimilarity(probe.vector(), own) >= CameraVerification.STORED_PHOTO_SIMILARITY;
        if ((!probe.usable() || stored) && fallbackPicture != null) {
            byte[] second = fallbackPicture.get();
            Probe retry = probe(second);
            if (retry.usable()
                    && FaceMatcher.maxSimilarity(retry.vector(), own) < CameraVerification.STORED_PHOTO_SIMILARITY) {
                probe = retry;
                stored = false;
            }
        }
        if (!probe.usable()) {
            return VerificationResult.notChecked(probe.reason());
        }
        if (stored) {
            // The picture is the employee's stored photo, not a live capture: it proves nothing.
            return VerificationResult.notChecked("STORED_PHOTO");
        }

        FaceMatcher.Aggregation aggregation = settings.aggregationMode();
        double cameraScore = FaceMatcher.scoreEmployee(cameraEmployee.getId(), probe.vector(), own, aggregation)
                .map(FaceMatcher.Candidate::score).orElse(0.0);
        Map<Long, List<FaceMatcher.Sample>> others = gallery.snapshot();
        others.remove(cameraEmployee.getId());
        List<FaceMatcher.Candidate> ranked = FaceMatcher.rank(probe.vector(), others, aggregation, 1);
        FaceMatcher.Candidate bestOther = ranked.isEmpty() ? null : ranked.get(0);
        double otherScore = bestOther != null ? bestOther.score() : 0.0;

        CameraVerification.Outcome outcome = CameraVerification.decide(cameraScore, otherScore,
                settings.getLowThreshold(), settings.getHighThreshold(), settings.getMinMargin());
        Employee other = bestOther == null ? null : employeeRepository.findById(bestOther.employeeDbId())
                .filter(e -> Boolean.TRUE.equals(e.getIsActive())).orElse(null);
        if (outcome == CameraVerification.Outcome.CONFLICT && other == null) {
            outcome = cameraScore >= settings.getLowThreshold()
                    ? CameraVerification.Outcome.AGREE : CameraVerification.Outcome.WEAK;
        }

        RecognitionResult recognition = null;
        if (outcome == CameraVerification.Outcome.AGREE && cameraScore >= settings.getHighThreshold()
                && cameraScore - otherScore >= settings.getMinMargin()) {
            recognition = new RecognitionResult(FaceMatcher.Band.HIGH, cameraEmployee, cameraScore, otherScore,
                    bestOther != null ? bestOther.employeeDbId() : null, "CONFIDENT", probe.face(), probe.vector(),
                    probe.picture());
        }
        return new VerificationResult(outcome, "CHECKED", cameraScore, other, otherScore, recognition);
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

    public double lowThreshold() {
        return settings.getLowThreshold();
    }

    public boolean verifyWeakGoesToReview() {
        return settings.verifyWeakGoesToReview();
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
