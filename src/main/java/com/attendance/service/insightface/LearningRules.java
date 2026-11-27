package com.attendance.service.insightface;

/**
 * Pure rules for turning a camera capture into a new reference sample ("learning"). Kept separate
 * from the Spring service so they are unit-testable.
 *
 * <p>Learning is deliberately conservative: one wrong sample would keep matching the wrong person,
 * so a capture is only learned when it is good quality, clearly this employee, not more like someone
 * else, and different enough from the photos the employee already has to add something.
 */
public final class LearningRules {

    private LearningRules() {
    }

    public enum Verdict {
        LEARN,
        SKIP_QUALITY,
        SKIP_NOT_SIMILAR,
        SKIP_LOOKS_LIKE_OTHER,
        SKIP_NOT_NOVEL,
        SKIP_LOW_SCORE,
        SKIP_SMALL_MARGIN,
        SKIP_TOO_SOON
    }

    /**
     * A capture a reviewer confirmed as this employee.
     *
     * @param qualityOk       the face passed the learning quality policy
     * @param hasOwnSamples   the employee already has samples to compare with
     * @param ownScore        score of the capture against the employee's own samples
     * @param minOwnScore     minimum own score (guards against a mistaken confirmation)
     * @param maxSimToOwn     highest similarity to any one existing sample
     * @param noveltyMax      learn only when {@code maxSimToOwn} is below this
     * @param bestOtherScore  best score against any other employee
     * @param highThreshold   automatic-match threshold
     */
    public static Verdict forReview(boolean qualityOk, boolean hasOwnSamples, double ownScore, double minOwnScore,
                                    double maxSimToOwn, double noveltyMax, double bestOtherScore,
                                    double highThreshold) {
        if (!qualityOk) {
            return Verdict.SKIP_QUALITY;
        }
        if (hasOwnSamples && ownScore < minOwnScore) {
            return Verdict.SKIP_NOT_SIMILAR;
        }
        if (bestOtherScore >= highThreshold && bestOtherScore > ownScore) {
            return Verdict.SKIP_LOOKS_LIKE_OTHER;
        }
        if (hasOwnSamples && maxSimToOwn >= noveltyMax) {
            return Verdict.SKIP_NOT_NOVEL;
        }
        return Verdict.LEARN;
    }

    /**
     * A capture InsightFace matched automatically with high confidence.
     *
     * @param hoursSinceLastAutoLearn hours since this employee last got an automatic sample, or null
     *                                when never
     */
    public static Verdict forAuto(boolean qualityOk, double score, double minScore, double margin, double minMargin,
                                  double maxSimToOwn, double noveltyMax, Double hoursSinceLastAutoLearn,
                                  double minIntervalHours) {
        if (!qualityOk) {
            return Verdict.SKIP_QUALITY;
        }
        if (score < minScore) {
            return Verdict.SKIP_LOW_SCORE;
        }
        if (margin < minMargin) {
            return Verdict.SKIP_SMALL_MARGIN;
        }
        if (hoursSinceLastAutoLearn != null && hoursSinceLastAutoLearn < minIntervalHours) {
            return Verdict.SKIP_TOO_SOON;
        }
        if (maxSimToOwn >= noveltyMax) {
            return Verdict.SKIP_NOT_NOVEL;
        }
        return Verdict.LEARN;
    }
}
