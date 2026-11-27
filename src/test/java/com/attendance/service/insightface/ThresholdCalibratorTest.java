package com.attendance.service.insightface;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ThresholdCalibratorTest {

    /** 30 employees x 4 photos; photos of one person are ~0.5 similar, different people ~0. */
    private static Map<Long, List<FaceMatcher.Sample>> gallery() {
        Map<Long, List<FaceMatcher.Sample>> g = new HashMap<>();
        long sampleId = 1;
        for (long e = 1; e <= 30; e++) {
            float[] identity = TestVectors.random(1000 + e, 512);
            List<FaceMatcher.Sample> list = new ArrayList<>();
            for (int s = 0; s < 4; s++) {
                list.add(new FaceMatcher.Sample(sampleId, TestVectors.withSimilarity(identity, 0.72, 5000 + sampleId)));
                sampleId++;
            }
            g.put(e, list);
        }
        return g;
    }

    @Test
    void separatesGenuineFromImpostorAndRecommends() {
        ThresholdCalibrator.Report r = ThresholdCalibrator.calibrate(gallery(), List.of(), 0.40, 0.60, 0.001, 0.98, 0);
        assertEquals(30, r.employees());
        assertEquals(120, r.samples());
        assertEquals(120, r.genuine().count());
        assertEquals(120, r.impostor().count());
        assertTrue(r.genuine().p05() > r.impostor().max(), "genuine p05 " + r.genuine().p05() + " impostor max " + r.impostor().max());
        // Few impostor scores: the recommendation stays above all of them, but never below 0.45.
        assertEquals(Double.valueOf(0.45), r.recommendedHigh());
        assertNotNull(r.recommendedLow());
        assertTrue(r.recommendedLow() >= 0.25 && r.recommendedLow() <= 0.40, "low " + r.recommendedLow());
        assertEquals(0.0, r.currentFar(), 1e-9);
        assertEquals("LOW", r.confidence());
    }

    @Test
    void falseAcceptRateFallsAsThresholdRises() {
        ThresholdCalibrator.Report r = ThresholdCalibrator.calibrate(gallery(), List.of(), 0.40, 0.60, 0.001, 0.98, 0);
        double previousFar = 1.0;
        double previousFrr = 0.0;
        for (ThresholdCalibrator.Row row : r.table()) {
            assertTrue(row.far() <= previousFar);
            assertTrue(row.frr() >= previousFrr);
            previousFar = row.far();
            previousFrr = row.frr();
        }
        int binned = r.histogram().stream().mapToInt(ThresholdCalibrator.Bin::genuine).sum();
        assertEquals(120, binned);
    }

    @Test
    void aRejectedHighScoringReviewRaisesTheRecommendation() {
        List<ThresholdCalibrator.ReviewPoint> reviews = List.of(
                new ThresholdCalibrator.ReviewPoint(0.66, false),
                new ThresholdCalibrator.ReviewPoint(0.55, true));
        ThresholdCalibrator.Report r = ThresholdCalibrator.calibrate(gallery(), reviews, 0.40, 0.60, 0.001, 0.98, 0);
        assertEquals(1, r.reviewRejected());
        assertEquals(1, r.reviewConfirmed());
        assertTrue(r.recommendedHigh() >= 0.69, "high " + r.recommendedHigh());
        assertTrue(r.currentFar() > 0.0);
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("below the recommended")));
        ThresholdCalibrator.Row at60 = r.table().stream().filter(x -> Math.abs(x.threshold() - 0.60) < 1e-9).findFirst().orElseThrow();
        assertEquals(1, at60.reviewsRejectedAbove());
        assertEquals(0, at60.reviewsConfirmedAbove());
    }

    @Test
    void probeCapAndEmptyGallery() {
        ThresholdCalibrator.Report capped = ThresholdCalibrator.calibrate(gallery(), List.of(), 0.40, 0.60, 0.001, 0.98, 40);
        assertTrue(capped.probesUsed() <= 40);
        ThresholdCalibrator.Report empty = ThresholdCalibrator.calibrate(new HashMap<>(), List.of(), 0.40, 0.60, 0.001, 0.98, 0);
        assertNull(empty.recommendedHigh());
        assertNull(empty.recommendedLow());
    }

    @Test
    void quantile() {
        double[] v = {0.0, 0.25, 0.5, 0.75, 1.0};
        assertEquals(0.5, ThresholdCalibrator.quantile(v, 0.5), 1e-9);
        assertEquals(0.05 * 4 * 0.25, ThresholdCalibrator.quantile(v, 0.05), 1e-9);
        assertEquals(0.4, ThresholdCalibrator.shareAtOrAbove(v, 0.75), 1e-9);
        assertEquals(0.6, ThresholdCalibrator.shareBelow(v, 0.75), 1e-9);
    }
}
