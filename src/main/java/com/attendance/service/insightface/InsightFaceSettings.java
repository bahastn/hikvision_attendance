package com.attendance.service.insightface;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * All InsightFace tuning knobs in one place (see the "InsightFace" section of
 * {@code application.properties} for what each one means and its environment variable).
 */
@Component
@Getter
public class InsightFaceSettings {

    @Value("${insightface.enabled:false}")
    private boolean enabled;

    @Value("${insightface.model:buffalo_l}")
    private String model;

    // ---- Matching -------------------------------------------------------------------------------

    @Value("${insightface.high-threshold:0.60}")
    private double highThreshold;

    @Value("${insightface.low-threshold:0.40}")
    private double lowThreshold;

    /** Minimum gap between the best and second-best employee for an automatic (HIGH) match. */
    @Value("${insightface.match.min-margin:0.06}")
    private double minMargin;

    /** MAX or TOP2_MEAN. */
    @Value("${insightface.match.aggregation:MAX}")
    private String aggregation;

    // ---- Probe (camera capture) quality ---------------------------------------------------------

    @Value("${insightface.min-det-score:0.50}")
    private double probeMinDetScore;

    @Value("${insightface.probe.min-face-size:48}")
    private double probeMinFaceSize;

    @Value("${insightface.probe.max-yaw:50}")
    private double probeMaxYaw;

    @Value("${insightface.probe.max-pitch:40}")
    private double probeMaxPitch;

    @Value("${insightface.probe.min-blur:0.4}")
    private double probeMinBlur;

    // ---- Reference sample quality ---------------------------------------------------------------

    @Value("${insightface.sample.min-det-score:0.70}")
    private double sampleMinDetScore;

    @Value("${insightface.sample.min-face-size:112}")
    private double sampleMinFaceSize;

    @Value("${insightface.sample.max-yaw:35}")
    private double sampleMaxYaw;

    @Value("${insightface.sample.max-pitch:30}")
    private double sampleMaxPitch;

    @Value("${insightface.sample.min-blur:2.0}")
    private double sampleMinBlur;

    /** Maximum number of samples (all sources) per employee. */
    @Value("${insightface.sample.max-per-employee:20}")
    private int maxSamplesPerEmployee;

    /** A new photo at least this similar to an existing one is rejected as a near-duplicate. */
    @Value("${insightface.sample.duplicate-similarity:0.95}")
    private double duplicateSimilarity;

    /** Uploaded/learned photos are scaled down to this longer side before storing. */
    @Value("${insightface.sample.max-image-side:1280}")
    private int maxImageSide;

    /** Reject an uploaded photo that matches a different employee better (protects from mix-ups). */
    @Value("${insightface.sample.identity-check:true}")
    private boolean identityCheck;

    // ---- Learning from captures (phase 3) -------------------------------------------------------

    /** Add a capture as a sample when a reviewer confirms it. */
    @Value("${insightface.learning.from-reviews:true}")
    private boolean learnFromReviews;

    /** Add very confident automatic matches as samples. Off by default; enable once thresholds are calibrated. */
    @Value("${insightface.learning.auto.enabled:false}")
    private boolean autoLearn;

    @Value("${insightface.learning.auto.min-score:0.70}")
    private double autoLearnMinScore;

    @Value("${insightface.learning.auto.min-margin:0.10}")
    private double autoLearnMinMargin;

    /** Only learn a capture that is different enough from the existing samples (adds variety). */
    @Value("${insightface.learning.novelty-max-similarity:0.90}")
    private double learnNoveltyMaxSimilarity;

    /** Cap on learned samples (REVIEW_CONFIRMED + AUTO_LEARNED) per employee; the oldest is replaced. */
    @Value("${insightface.learning.max-learned-per-employee:8}")
    private int maxLearnedPerEmployee;

    /** A confirmed capture must still score at least this against the employee's own photos. */
    @Value("${insightface.learning.review-min-score:0.30}")
    private double reviewLearnMinScore;

    /** Quality rules for a camera capture to become a sample (captures are smaller than uploads). */
    @Value("${insightface.learning.min-det-score:0.60}")
    private double learnMinDetScore;

    @Value("${insightface.learning.min-face-size:80}")
    private double learnMinFaceSize;

    @Value("${insightface.learning.max-yaw:40}")
    private double learnMaxYaw;

    @Value("${insightface.learning.max-pitch:35}")
    private double learnMaxPitch;

    @Value("${insightface.learning.min-blur:1.5}")
    private double learnMinBlur;

    /** Minimum hours between two automatic learnings for the same employee. */
    @Value("${insightface.learning.auto.min-interval-hours:24}")
    private long autoLearnMinIntervalHours;

    // ---- Second opinion on camera matches (phase 5) ---------------------------------------------

    @Value("${insightface.verify.enabled:true}")
    private boolean verifyCameraMatches;

    /** What to do when InsightFace cannot confirm the camera's match: REVIEW or ACCEPT. */
    @Value("${insightface.verify.on-weak:REVIEW}")
    private String verifyOnWeak;

    // ---- Calibration (phase 4) ------------------------------------------------------------------

    /** Target false-accept rate used to recommend the high threshold (e.g. 0.001 = 0.1%). */
    @Value("${insightface.calibration.target-far:0.001}")
    private double calibrationTargetFar;

    /** Share of genuine matches the low threshold should still catch for review (e.g. 0.98). */
    @Value("${insightface.calibration.target-review-recall:0.98}")
    private double calibrationTargetReviewRecall;

    public FaceMatcher.Aggregation aggregationMode() {
        return FaceMatcher.Aggregation.parse(aggregation);
    }

    public FaceQuality.Policy probePolicy() {
        return new FaceQuality.Policy(probeMinDetScore, probeMinFaceSize, probeMaxYaw, probeMaxPitch, probeMinBlur);
    }

    public FaceQuality.Policy samplePolicy() {
        return new FaceQuality.Policy(sampleMinDetScore, sampleMinFaceSize, sampleMaxYaw, sampleMaxPitch, sampleMinBlur);
    }

    public FaceQuality.Policy learnPolicy() {
        return new FaceQuality.Policy(learnMinDetScore, learnMinFaceSize, learnMaxYaw, learnMaxPitch, learnMinBlur);
    }

    public boolean verifyWeakGoesToReview() {
        return !"ACCEPT".equalsIgnoreCase(verifyOnWeak == null ? "" : verifyOnWeak.trim());
    }
}
