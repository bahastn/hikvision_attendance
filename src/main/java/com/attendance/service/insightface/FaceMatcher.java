package com.attendance.service.insightface;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure face-matching math: cosine similarity, multi-sample gallery ranking, and turning the ranked
 * candidates into a confidence {@link Band}. No Spring, database or network dependencies, so it is
 * directly unit-testable.
 *
 * <p>The gallery holds several samples (photos) per employee. An employee's score against a probe is
 * aggregated over their samples ({@link Aggregation}); the decision then also looks at the gap to
 * the runner-up employee, so two similar-looking people are sent to human review instead of being
 * guessed.
 */
public final class FaceMatcher {

    private FaceMatcher() {
    }

    /** Confidence bands for a recognition result. */
    public enum Band {
        /** Confident enough to record attendance automatically. */
        HIGH,
        /** A plausible match that needs a human to confirm. */
        UNCERTAIN,
        /** Not a match (or no usable face). */
        NO_MATCH
    }

    /** How an employee's score is computed from their individual sample scores. */
    public enum Aggregation {
        /** Best single sample. Most forgiving for unusual angles/lighting covered by one photo. */
        MAX,
        /** Mean of the two best samples (falls back to MAX with one sample). Less sensitive to a
         *  single outlier photo. */
        TOP2_MEAN;

        public static Aggregation parse(String value) {
            if (value == null) {
                return MAX;
            }
            try {
                return Aggregation.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return MAX;
            }
        }
    }

    /** One stored sample: its id and its (normalized) embedding. */
    public record Sample(Long sampleId, float[] vector) {
    }

    /** One ranked employee: database id, aggregated score and the sample that scored best. */
    public record Candidate(Long employeeDbId, double score, Long bestSampleId) {
    }

    /** Why a decision came out the way it did (for logs, review notes and diagnostics). */
    public enum Reason {
        CONFIDENT, BELOW_HIGH, AMBIGUOUS, BELOW_LOW, EMPTY_GALLERY
    }

    /** Outcome of {@link #decide}: band, best and runner-up candidates, and the margin between them. */
    public record Decision(Band band, Candidate best, Candidate runnerUp, double margin, Reason reason) {
    }

    /**
     * Cosine similarity of two vectors. When both are already L2-normalized this equals their dot
     * product; dividing by the norms keeps it correct for un-normalized input too.
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
     * Score one employee's samples against a probe. Returns empty when there are no usable samples.
     */
    public static Optional<Candidate> scoreEmployee(Long employeeDbId, float[] probe, List<Sample> samples,
                                                    Aggregation aggregation) {
        if (probe == null || probe.length == 0 || samples == null || samples.isEmpty()) {
            return Optional.empty();
        }
        double best = Double.NEGATIVE_INFINITY;
        double second = Double.NEGATIVE_INFINITY;
        Long bestSampleId = null;
        int scored = 0;
        for (Sample sample : samples) {
            if (sample == null || sample.vector() == null || sample.vector().length != probe.length) {
                continue;
            }
            double s = cosine(probe, sample.vector());
            scored++;
            if (s > best) {
                second = best;
                best = s;
                bestSampleId = sample.sampleId();
            } else if (s > second) {
                second = s;
            }
        }
        if (scored == 0) {
            return Optional.empty();
        }
        double score = (aggregation == Aggregation.TOP2_MEAN && scored >= 2) ? (best + second) / 2.0 : best;
        return Optional.of(new Candidate(employeeDbId, score, bestSampleId));
    }

    /**
     * Rank every employee in the gallery against the probe, best first. {@code limit <= 0} returns
     * all of them.
     */
    public static List<Candidate> rank(float[] probe, Map<Long, List<Sample>> gallery, Aggregation aggregation,
                                       int limit) {
        List<Candidate> out = new ArrayList<>();
        if (probe == null || probe.length == 0 || gallery == null || gallery.isEmpty()) {
            return out;
        }
        for (Map.Entry<Long, List<Sample>> entry : gallery.entrySet()) {
            scoreEmployee(entry.getKey(), probe, entry.getValue(), aggregation).ifPresent(out::add);
        }
        out.sort(Comparator.comparingDouble(Candidate::score).reversed());
        if (limit > 0 && out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    /**
     * Turn ranked candidates into a decision:
     * <ul>
     *   <li>best &ge; high and (no runner-up or best &minus; runner-up &ge; minMargin) → HIGH</li>
     *   <li>best &ge; high but too close to the runner-up → UNCERTAIN (AMBIGUOUS)</li>
     *   <li>low &le; best &lt; high → UNCERTAIN</li>
     *   <li>otherwise → NO_MATCH</li>
     * </ul>
     */
    public static Decision decide(List<Candidate> ranked, double lowThreshold, double highThreshold,
                                  double minMargin) {
        if (ranked == null || ranked.isEmpty()) {
            return new Decision(Band.NO_MATCH, null, null, 0.0, Reason.EMPTY_GALLERY);
        }
        Candidate best = ranked.get(0);
        Candidate runnerUp = ranked.size() > 1 ? ranked.get(1) : null;
        double margin = runnerUp == null ? 1.0 : best.score() - runnerUp.score();

        if (best.score() >= highThreshold) {
            if (margin >= minMargin) {
                return new Decision(Band.HIGH, best, runnerUp, margin, Reason.CONFIDENT);
            }
            return new Decision(Band.UNCERTAIN, best, runnerUp, margin, Reason.AMBIGUOUS);
        }
        if (best.score() >= lowThreshold) {
            return new Decision(Band.UNCERTAIN, best, runnerUp, margin, Reason.BELOW_HIGH);
        }
        return new Decision(Band.NO_MATCH, best, runnerUp, margin, Reason.BELOW_LOW);
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

    /** Highest similarity between a vector and any of the given samples (0 when none). */
    public static double maxSimilarity(float[] vector, List<Sample> samples) {
        double best = 0.0;
        if (vector == null || samples == null) {
            return best;
        }
        for (Sample s : samples) {
            if (s != null && s.vector() != null) {
                best = Math.max(best, cosine(vector, s.vector()));
            }
        }
        return best;
    }
}
