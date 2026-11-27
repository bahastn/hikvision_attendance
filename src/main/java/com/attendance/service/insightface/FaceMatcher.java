package com.attendance.service.insightface;

import java.util.Map;
import java.util.Optional;

/**
 * Pure face-matching math: cosine similarity, best-match search over a gallery, and mapping a
 * similarity score to a confidence {@link Band}. No Spring, database or network dependencies, so it
 * is directly unit-testable.
 */
public final class FaceMatcher {

    private FaceMatcher() {
    }

    /** Confidence bands for a fallback recognition result. */
    public enum Band {
        /** Confident enough to record attendance automatically. */
        HIGH,
        /** A plausible match that needs a human to confirm. */
        UNCERTAIN,
        /** Not a match (or no usable face). */
        NO_MATCH
    }

    /** One gallery hit: the employee's database id and the cosine similarity to the probe. */
    public record Candidate(Long employeeDbId, double score) {
    }

    /**
     * Cosine similarity of two vectors. When both are already L2-normalized this is just their dot
     * product; this method still divides by the norms so it is correct for un-normalized input too.
     * Returns 0 for null/empty/length-mismatched vectors.
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Find the gallery entry most similar to {@code probe}. The gallery maps employee database id to
     * a (normalized) embedding. Returns empty when the gallery or probe is empty.
     */
    public static Optional<Candidate> bestMatch(float[] probe, Map<Long, float[]> gallery) {
        if (probe == null || probe.length == 0 || gallery == null || gallery.isEmpty()) {
            return Optional.empty();
        }
        Long bestId = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<Long, float[]> entry : gallery.entrySet()) {
            double score = cosine(probe, entry.getValue());
            if (score > bestScore) {
                bestScore = score;
                bestId = entry.getKey();
            }
        }
        if (bestId == null) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(bestId, bestScore));
    }

    /**
     * Map a cosine score to a band:
     * {@code score >= high} → HIGH, {@code low <= score < high} → UNCERTAIN, else NO_MATCH.
     */
    public static Band band(double score, double lowThreshold, double highThreshold) {
        if (score >= highThreshold) {
            return Band.HIGH;
        }
        if (score >= lowThreshold) {
            return Band.UNCERTAIN;
        }
        return Band.NO_MATCH;
    }
}
