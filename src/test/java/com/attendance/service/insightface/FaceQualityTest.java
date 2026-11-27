package com.attendance.service.insightface;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FaceQualityTest {

    private static final FaceQuality.Policy SAMPLE = new FaceQuality.Policy(0.70, 112, 35, 30, 2.0);

    private static DetectedFace face(double det, double size, Double yaw, Double pitch, Double blur, float x) {
        float[] bbox = {x, 0, (float) (x + size), (float) size};
        return new DetectedFace(TestVectors.random((long) (det * 1000 + size), 16), det, bbox, size, yaw, pitch, blur);
    }

    @Test
    void goodFacePasses() {
        FaceQuality.Assessment a = FaceQuality.assessEnrollment(List.of(face(0.92, 200, 5.0, 3.0, 6.5, 0)), SAMPLE);
        assertTrue(a.ok());
        assertTrue(a.score() > 0.8, "score " + a.score());
    }

    @Test
    void eachRuleRejects() {
        assertEquals(FaceQuality.Issue.NO_FACE, FaceQuality.assessEnrollment(List.of(), SAMPLE).issue());
        assertEquals(FaceQuality.Issue.LOW_DETECTION, FaceQuality.assess(face(0.5, 200, 0.0, 0.0, 6.5, 0), SAMPLE).issue());
        assertEquals(FaceQuality.Issue.FACE_TOO_SMALL, FaceQuality.assess(face(0.9, 80, 0.0, 0.0, 6.5, 0), SAMPLE).issue());
        assertEquals(FaceQuality.Issue.TURNED_AWAY, FaceQuality.assess(face(0.9, 200, -50.0, 0.0, 6.5, 0), SAMPLE).issue());
        assertEquals(FaceQuality.Issue.TILTED, FaceQuality.assess(face(0.9, 200, 0.0, 40.0, 6.5, 0), SAMPLE).issue());
        assertEquals(FaceQuality.Issue.BLURRY, FaceQuality.assess(face(0.9, 200, 0.0, 0.0, 0.8, 0), SAMPLE).issue());
    }

    @Test
    void unknownMeasurementsDoNotReject() {
        // An older sidecar only reports the detector score.
        DetectedFace legacy = new DetectedFace(TestVectors.random(1, 16), 0.9);
        assertTrue(FaceQuality.assess(legacy, SAMPLE).ok());
    }

    @Test
    void twoBigFacesAreRejectedButSmallBackgroundFaceIsIgnored() {
        DetectedFace main = face(0.95, 200, 0.0, 0.0, 6.5, 0);
        DetectedFace other = face(0.90, 180, 0.0, 0.0, 6.5, 300);
        DetectedFace tiny = face(0.80, 40, 0.0, 0.0, 6.5, 600);
        assertEquals(FaceQuality.Issue.MULTIPLE_FACES, FaceQuality.assessEnrollment(List.of(main, other), SAMPLE).issue());
        assertTrue(FaceQuality.assessEnrollment(List.of(main, tiny), SAMPLE).ok());
    }

    @Test
    void primaryFaceIsTheLargestConfidentFace() {
        DetectedFace small = face(0.99, 60, 0.0, 0.0, 6.5, 0);
        DetectedFace big = face(0.80, 150, 0.0, 0.0, 6.5, 100);
        DetectedFace bigButUnsure = face(0.30, 300, 0.0, 0.0, 6.5, 300);
        assertEquals(big, FaceQuality.primaryFace(List.of(small, big, bigButUnsure), 0.5));
        // Nothing passes the detector threshold: fall back to the most confident.
        assertEquals(small, FaceQuality.primaryFace(List.of(face(0.4, 300, 0.0, 0.0, 0.1, 0), small), 0.995));
        assertNull(FaceQuality.primaryFace(List.of(), 0.5));
    }

    @Test
    void scoreIsBetweenZeroAndOne() {
        double low = FaceQuality.score(face(0.3, 20, 80.0, 60.0, 0.1, 0), SAMPLE);
        double high = FaceQuality.score(face(0.99, 400, 0.0, 0.0, 20.0, 0), SAMPLE);
        assertTrue(low >= 0 && low < 0.4, "low " + low);
        assertEquals(1.0, high, 1e-9);
    }
}
