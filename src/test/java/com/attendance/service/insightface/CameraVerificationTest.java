package com.attendance.service.insightface;

import org.junit.jupiter.api.Test;

import static com.attendance.service.insightface.CameraVerification.Outcome.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CameraVerificationTest {

    private static final double LOW = 0.40;
    private static final double HIGH = 0.60;
    private static final double MARGIN = 0.06;

    @Test
    void agreesWhenTheFaceLooksLikeTheCamerasEmployee() {
        assertEquals(AGREE, CameraVerification.decide(0.55, 0.20, LOW, HIGH, MARGIN));
        assertEquals(AGREE, CameraVerification.decide(0.80, 0.30, LOW, HIGH, MARGIN));
    }

    @Test
    void conflictWhenSomeoneElseIsClearlyBetter() {
        assertEquals(CONFLICT, CameraVerification.decide(0.30, 0.70, LOW, HIGH, MARGIN));
        // Even when the camera's employee is plausible, a clearly better confident match wins.
        assertEquals(CONFLICT, CameraVerification.decide(0.45, 0.72, LOW, HIGH, MARGIN));
    }

    @Test
    void noConflictWhenTheOtherIsNotConfidentOrNotClearlyBetter() {
        assertEquals(AGREE, CameraVerification.decide(0.62, 0.65, LOW, HIGH, MARGIN));
        assertEquals(WEAK, CameraVerification.decide(0.20, 0.55, LOW, HIGH, MARGIN));
    }

    @Test
    void weakWhenNobodyMatches() {
        assertEquals(WEAK, CameraVerification.decide(0.25, 0.15, LOW, HIGH, MARGIN));
    }
}
