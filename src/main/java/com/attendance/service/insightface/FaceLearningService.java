package com.attendance.service.insightface;

import com.attendance.entity.Employee;
import com.attendance.entity.EmployeeFaceSample;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Phase 3: the gallery learns from real camera captures, which are the most representative
 * reference photos there are (same camera, angle and lighting as everyday recognition).
 *
 * <ul>
 *   <li><b>From reviews</b> ({@code insightface.learning.from-reviews}, on by default): when a reviewer
 *       confirms a pending capture, it is added as a REVIEW_CONFIRMED sample.</li>
 *   <li><b>Automatic</b> ({@code insightface.learning.auto.enabled}, off by default): a very confident
 *       automatic match (high score and a large margin) is added as an AUTO_LEARNED sample, at most
 *       once per employee per {@code min-interval-hours}.</li>
 * </ul>
 * In both cases the capture must pass {@link LearningRules}. Learned samples are capped per employee
 * (oldest replaced first), shown in the employee's Face photos dialog, and can be deleted there.
 * Everything here is best-effort: failures are logged and never affect attendance.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaceLearningService {

    private final InsightFaceClient client;
    private final InsightFaceGallery gallery;
    private final InsightFaceSettings settings;
    private final FaceSampleService sampleService;

    /** A reviewer confirmed a capture: maybe learn it (after the confirmation committed, off-thread). */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCaptureConfirmed(FaceEmbeddingEvents.CaptureConfirmed event) {
        if (!settings.isEnabled() || !client.isEnabled() || !settings.isLearnFromReviews()
                || event.employeeDbId() == null) {
            return;
        }
        try {
            learnFromReview(event);
        } catch (Exception e) {
            log.warn("Learning from confirmed capture #{} failed: {}", event.captureId(), e.getMessage());
        }
    }

    private void learnFromReview(FaceEmbeddingEvents.CaptureConfirmed event) throws Exception {
        byte[] jpeg = sampleService.readStoredImage(event.imageUrl());
        List<DetectedFace> faces = client.embed(jpeg);
        DetectedFace face = FaceQuality.primaryFace(faces, settings.getLearnMinDetScore());
        if (face == null) {
            log.info("Confirmed capture #{} not learned: no face found", event.captureId());
            return;
        }
        FaceQuality.Assessment quality = FaceQuality.assess(face, settings.learnPolicy());
        float[] vector = EmbeddingCodec.normalize(face.embedding());

        List<FaceMatcher.Sample> own = gallery.samplesOf(event.employeeDbId());
        double ownScore = FaceMatcher.scoreEmployee(event.employeeDbId(), vector, own, settings.aggregationMode())
                .map(FaceMatcher.Candidate::score).orElse(0.0);
        double maxSim = FaceMatcher.maxSimilarity(vector, own);
        double bestOther = bestOtherScore(event.employeeDbId(), vector);

        LearningRules.Verdict verdict = LearningRules.forReview(quality.ok(), !own.isEmpty(), ownScore,
                settings.getReviewLearnMinScore(), maxSim, settings.getLearnNoveltyMaxSimilarity(), bestOther,
                settings.getHighThreshold());
        if (verdict != LearningRules.Verdict.LEARN) {
            log.info("Confirmed capture #{} not learned for employee #{}: {} (own {}, nearest {}, other {}{})",
                    event.captureId(), event.employeeDbId(), verdict, fmt(ownScore), fmt(maxSim), fmt(bestOther),
                    quality.ok() ? "" : ", " + quality.detail());
            return;
        }
        var sample = sampleService.addLearnedSample(event.employeeDbId(), jpeg, face, vector, quality.score(),
                EmployeeFaceSample.Source.REVIEW_CONFIRMED, event.captureId(), own.isEmpty() ? null : ownScore,
                event.username());
        if (sample != null) {
            log.info("Learned confirmed capture #{} as face sample #{} for employee #{} (own score {})",
                    event.captureId(), sample.getId(), event.employeeDbId(), fmt(ownScore));
        }
    }

    /**
     * Consider adding a confident automatic match as a sample. Runs off the event thread; uses the
     * embedding already computed during recognition, so no extra sidecar call is made.
     */
    @Async
    public void considerAutoLearn(Employee employee, InsightFaceService.RecognitionResult result) {
        if (!settings.isEnabled() || !settings.isAutoLearn() || employee == null || employee.getId() == null
                || result == null || result.face() == null || result.probe() == null || result.picture() == null) {
            return;
        }
        try {
            FaceQuality.Assessment quality = FaceQuality.assess(result.face(), settings.learnPolicy());
            List<FaceMatcher.Sample> own = gallery.samplesOf(employee.getId());
            double maxSim = FaceMatcher.maxSimilarity(result.probe(), own);
            LocalDateTime last = sampleService.lastAutoLearnedAt(employee.getId());
            Double hoursSince = last == null ? null : Duration.between(last, LocalDateTime.now()).toMinutes() / 60.0;

            LearningRules.Verdict verdict = LearningRules.forAuto(quality.ok(), result.score(),
                    settings.getAutoLearnMinScore(), result.margin(), settings.getAutoLearnMinMargin(), maxSim,
                    settings.getLearnNoveltyMaxSimilarity(), hoursSince, settings.getAutoLearnMinIntervalHours());
            if (verdict != LearningRules.Verdict.LEARN) {
                log.debug("Auto-learn skipped for {}: {}", employee.getEmployeeId(), verdict);
                return;
            }
            var sample = sampleService.addLearnedSample(employee.getId(), result.picture(), result.face(),
                    result.probe(), quality.score(), EmployeeFaceSample.Source.AUTO_LEARNED, null, result.score(),
                    "system");
            if (sample != null) {
                log.info("Auto-learned face sample #{} for employee {} (score {}, margin {})", sample.getId(),
                        employee.getEmployeeId(), fmt(result.score()), fmt(result.margin()));
            }
        } catch (Exception e) {
            log.warn("Auto-learning for employee {} failed: {}", employee.getEmployeeId(), e.getMessage());
        }
    }

    private double bestOtherScore(Long employeeDbId, float[] vector) {
        Map<Long, List<FaceMatcher.Sample>> others = gallery.snapshot();
        others.remove(employeeDbId);
        List<FaceMatcher.Candidate> ranked = FaceMatcher.rank(vector, others, settings.aggregationMode(), 1);
        return ranked.isEmpty() ? 0.0 : ranked.get(0).score();
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
