package com.attendance.service.insightface;

import org.junit.jupiter.api.Test;

import static com.attendance.service.insightface.LearningRules.Verdict.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LearningRulesTest {

    private static final double HIGH = 0.60;

    @Test
    void reviewLearnsAGoodNovelCapture() {
        assertEquals(LEARN, LearningRules.forReview(true, true, 0.48, 0.30, 0.55, 0.90, 0.20, HIGH));
    }

    @Test
    void reviewLearnsWhenEmployeeHasNoSamplesYet() {
        assertEquals(LEARN, LearningRules.forReview(true, false, 0.0, 0.30, 0.0, 0.90, 0.10, HIGH));
    }

    @Test
    void reviewSkips() {
        assertEquals(SKIP_QUALITY, LearningRules.forReview(false, true, 0.5, 0.30, 0.5, 0.90, 0.1, HIGH));
        assertEquals(SKIP_NOT_SIMILAR, LearningRules.forReview(true, true, 0.20, 0.30, 0.2, 0.90, 0.1, HIGH));
        assertEquals(SKIP_LOOKS_LIKE_OTHER, LearningRules.forReview(true, true, 0.45, 0.30, 0.45, 0.90, 0.66, HIGH));
        assertEquals(SKIP_NOT_NOVEL, LearningRules.forReview(true, true, 0.93, 0.30, 0.93, 0.90, 0.1, HIGH));
    }

    @Test
    void autoRules() {
        assertEquals(LEARN, LearningRules.forAuto(true, 0.74, 0.70, 0.25, 0.10, 0.80, 0.90, null, 24));
        assertEquals(LEARN, LearningRules.forAuto(true, 0.74, 0.70, 0.25, 0.10, 0.80, 0.90, 30.0, 24));
        assertEquals(SKIP_QUALITY, LearningRules.forAuto(false, 0.74, 0.70, 0.25, 0.10, 0.80, 0.90, null, 24));
        assertEquals(SKIP_LOW_SCORE, LearningRules.forAuto(true, 0.65, 0.70, 0.25, 0.10, 0.60, 0.90, null, 24));
        assertEquals(SKIP_SMALL_MARGIN, LearningRules.forAuto(true, 0.74, 0.70, 0.05, 0.10, 0.70, 0.90, null, 24));
        assertEquals(SKIP_TOO_SOON, LearningRules.forAuto(true, 0.74, 0.70, 0.25, 0.10, 0.70, 0.90, 3.0, 24));
        assertEquals(SKIP_NOT_NOVEL, LearningRules.forAuto(true, 0.95, 0.70, 0.25, 0.10, 0.95, 0.90, null, 24));
    }
}
