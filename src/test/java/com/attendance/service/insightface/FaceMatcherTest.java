package com.attendance.service.insightface;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FaceMatcherTest {

    private static final int DIM = 512;

    @Test
    void cosineOfIdenticalAndMismatchedVectors() {
        float[] a = TestVectors.random(1, DIM);
        assertEquals(1.0, FaceMatcher.cosine(a, a), 1e-6);
        assertEquals(0.0, FaceMatcher.cosine(a, new float[3]), 1e-9);
        assertEquals(0.0, FaceMatcher.cosine(null, a), 1e-9);
    }

    @Test
    void testVectorsHaveRequestedSimilarity() {
        float[] a = TestVectors.random(2, DIM);
        assertEquals(0.55, FaceMatcher.cosine(a, TestVectors.withSimilarity(a, 0.55, 3)), 1e-3);
    }

    @Test
    void employeeScoreUsesBestSampleWithMax() {
        float[] probe = TestVectors.random(10, DIM);
        List<FaceMatcher.Sample> samples = List.of(
                new FaceMatcher.Sample(1L, TestVectors.withSimilarity(probe, 0.30, 11)),
                new FaceMatcher.Sample(2L, TestVectors.withSimilarity(probe, 0.72, 12)),
                new FaceMatcher.Sample(3L, TestVectors.withSimilarity(probe, 0.50, 13)));
        FaceMatcher.Candidate c = FaceMatcher.scoreEmployee(7L, probe, samples, FaceMatcher.Aggregation.MAX).orElseThrow();
        assertEquals(0.72, c.score(), 1e-3);
        assertEquals(Long.valueOf(2L), c.bestSampleId());
        FaceMatcher.Candidate top2 = FaceMatcher.scoreEmployee(7L, probe, samples, FaceMatcher.Aggregation.TOP2_MEAN).orElseThrow();
        assertEquals((0.72 + 0.50) / 2, top2.score(), 1e-3);
    }

    @Test
    void top2MeanFallsBackToMaxWithOneSample() {
        float[] probe = TestVectors.random(20, DIM);
        List<FaceMatcher.Sample> one = List.of(new FaceMatcher.Sample(1L, TestVectors.withSimilarity(probe, 0.66, 21)));
        assertEquals(0.66, FaceMatcher.scoreEmployee(1L, probe, one, FaceMatcher.Aggregation.TOP2_MEAN)
                .orElseThrow().score(), 1e-3);
    }

    @Test
    void extraSamplesRescueAnUnusualAngle() {
        // The enrollment photo alone scores below the high threshold; an extra photo matches well.
        float[] probe = TestVectors.random(30, DIM);
        Map<Long, List<FaceMatcher.Sample>> single = new HashMap<>();
        single.put(1L, List.of(new FaceMatcher.Sample(1L, TestVectors.withSimilarity(probe, 0.45, 31))));
        single.put(2L, List.of(new FaceMatcher.Sample(2L, TestVectors.withSimilarity(probe, 0.10, 32))));
        FaceMatcher.Decision before = FaceMatcher.decide(
                FaceMatcher.rank(probe, single, FaceMatcher.Aggregation.MAX, 2), 0.40, 0.60, 0.06);
        assertEquals(FaceMatcher.Band.UNCERTAIN, before.band());

        Map<Long, List<FaceMatcher.Sample>> multi = new HashMap<>(single);
        multi.put(1L, List.of(single.get(1L).get(0),
                new FaceMatcher.Sample(3L, TestVectors.withSimilarity(probe, 0.68, 33))));
        FaceMatcher.Decision after = FaceMatcher.decide(
                FaceMatcher.rank(probe, multi, FaceMatcher.Aggregation.MAX, 2), 0.40, 0.60, 0.06);
        assertEquals(FaceMatcher.Band.HIGH, after.band());
        assertEquals(Long.valueOf(1L), after.best().employeeDbId());
        assertEquals(Long.valueOf(3L), after.best().bestSampleId());
    }

    @Test
    void rankIsSortedAndLimited() {
        float[] probe = TestVectors.random(40, DIM);
        Map<Long, List<FaceMatcher.Sample>> g = new HashMap<>();
        double[] sims = {0.2, 0.8, 0.5, 0.65};
        for (int i = 0; i < sims.length; i++) {
            g.put((long) i, List.of(new FaceMatcher.Sample((long) i, TestVectors.withSimilarity(probe, sims[i], 41 + i))));
        }
        List<FaceMatcher.Candidate> ranked = FaceMatcher.rank(probe, g, FaceMatcher.Aggregation.MAX, 2);
        assertEquals(2, ranked.size());
        assertEquals(Long.valueOf(1L), ranked.get(0).employeeDbId());
        assertEquals(Long.valueOf(3L), ranked.get(1).employeeDbId());
        assertEquals(4, FaceMatcher.rank(probe, g, FaceMatcher.Aggregation.MAX, 0).size());
    }

    @Test
    void decideBands() {
        FaceMatcher.Candidate a = new FaceMatcher.Candidate(1L, 0.75, 1L);
        FaceMatcher.Candidate close = new FaceMatcher.Candidate(2L, 0.72, 2L);
        FaceMatcher.Candidate far = new FaceMatcher.Candidate(2L, 0.40, 2L);

        assertEquals(FaceMatcher.Band.HIGH, FaceMatcher.decide(List.of(a), 0.4, 0.6, 0.06).band());
        assertEquals(FaceMatcher.Band.HIGH, FaceMatcher.decide(List.of(a, far), 0.4, 0.6, 0.06).band());

        FaceMatcher.Decision ambiguous = FaceMatcher.decide(List.of(a, close), 0.4, 0.6, 0.06);
        assertEquals(FaceMatcher.Band.UNCERTAIN, ambiguous.band());
        assertEquals(FaceMatcher.Reason.AMBIGUOUS, ambiguous.reason());
        assertEquals(0.03, ambiguous.margin(), 1e-9);

        assertEquals(FaceMatcher.Reason.BELOW_HIGH,
                FaceMatcher.decide(List.of(new FaceMatcher.Candidate(1L, 0.5, 1L)), 0.4, 0.6, 0.06).reason());
        assertEquals(FaceMatcher.Band.NO_MATCH,
                FaceMatcher.decide(List.of(new FaceMatcher.Candidate(1L, 0.3, 1L)), 0.4, 0.6, 0.06).band());
        assertEquals(FaceMatcher.Reason.EMPTY_GALLERY, FaceMatcher.decide(List.of(), 0.4, 0.6, 0.06).reason());
    }

    @Test
    void aggregationParsing() {
        assertEquals(FaceMatcher.Aggregation.TOP2_MEAN, FaceMatcher.Aggregation.parse(" top2_mean "));
        assertEquals(FaceMatcher.Aggregation.MAX, FaceMatcher.Aggregation.parse("nonsense"));
        assertEquals(FaceMatcher.Aggregation.MAX, FaceMatcher.Aggregation.parse(null));
    }

    @Test
    void maxSimilarityAgainstSamples() {
        float[] v = TestVectors.random(50, DIM);
        List<FaceMatcher.Sample> s = List.of(new FaceMatcher.Sample(1L, TestVectors.withSimilarity(v, 0.3, 51)),
                new FaceMatcher.Sample(2L, TestVectors.withSimilarity(v, 0.96, 52)));
        assertEquals(0.96, FaceMatcher.maxSimilarity(v, s), 1e-3);
        assertEquals(0.0, FaceMatcher.maxSimilarity(v, List.of()), 1e-9);
    }
}
