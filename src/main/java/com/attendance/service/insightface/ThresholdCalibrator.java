package com.attendance.service.insightface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Pure threshold calibration (phase 4). Measures, on <em>your</em> data, how scores of the same
 * person ("genuine") and of different people ("impostor") are distributed, and recommends
 * thresholds from that. No Spring dependencies.
 *
 * <p>Data used:
 * <ul>
 *   <li><b>Gallery, leave-one-out</b>: each sample of an employee with 2+ samples is treated as a
 *       probe. Its score against the employee's <em>other</em> samples is a genuine score; its best
 *       score against every other employee is an impostor score (exactly what the matcher would
 *       compare against the high threshold).</li>
 *   <li><b>Review decisions</b>: suggestions a reviewer confirmed are genuine scores, rejected ones
 *       are impostor scores. These are real camera conditions and the most valuable data.</li>
 * </ul>
 * Recommendations: the high threshold is the lowest value whose false-accept rate is at most the
 * target (and, when there is too little impostor data to estimate a 0.1% tail, safely above every
 * impostor score seen); the low threshold still sends the target share of genuine matches to review.
 */
public final class ThresholdCalibrator {

    private ThresholdCalibrator() {
    }

    /** A human review decision: the suggestion's score and whether it was the right person. */
    public record ReviewPoint(double score, boolean genuine) {
    }

    public record Stats(int count, double min, double p05, double median, double p95, double max, double mean) {
        static Stats of(double[] sorted) {
            if (sorted.length == 0) {
                return new Stats(0, 0, 0, 0, 0, 0, 0);
            }
            double sum = 0;
            for (double v : sorted) {
                sum += v;
            }
            return new Stats(sorted.length, sorted[0], quantile(sorted, 0.05), quantile(sorted, 0.5),
                    quantile(sorted, 0.95), sorted[sorted.length - 1], sum / sorted.length);
        }
    }

    public record Bin(double from, double to, int genuine, int impostor) {
    }

    /**
     * One row of the threshold table.
     *
     * @param far                   share of impostor scores at or above the threshold (wrong person accepted)
     * @param frr                   share of genuine scores below the threshold (right person missed)
     * @param reviewsRejectedAbove  rejected review suggestions that scored at or above the threshold
     * @param reviewsConfirmedAbove confirmed review suggestions that scored at or above the threshold
     */
    public record Row(double threshold, double far, double frr, int reviewsRejectedAbove, int reviewsConfirmedAbove) {
    }

    public record Report(int employees, int samples, int employeesWithSeveralSamples, int probesUsed,
                         int reviewConfirmed, int reviewRejected,
                         Stats genuine, Stats impostor, List<Bin> histogram, List<Row> table,
                         double currentLow, double currentHigh, double currentFar, double currentFrr,
                         Double recommendedHigh, Double recommendedLow, String confidence, List<String> notes) {
    }

    /**
     * Build the calibration report.
     *
     * @param gallery     employee id → samples
     * @param reviews     human review decisions (may be empty)
     * @param maxProbes   cap on leave-one-out probes (the cost is probes × total samples)
     */
    public static Report calibrate(Map<Long, List<FaceMatcher.Sample>> gallery, List<ReviewPoint> reviews,
                                   double currentLow, double currentHigh, double targetFar, double targetRecall,
                                   int maxProbes) {
        int employees = gallery.size();
        int samples = gallery.values().stream().mapToInt(List::size).sum();
        int several = (int) gallery.values().stream().filter(l -> l.size() >= 2).count();

        // Leave-one-out over the gallery.
        List<Long> probeOwners = new ArrayList<>();
        List<FaceMatcher.Sample> probes = new ArrayList<>();
        for (Map.Entry<Long, List<FaceMatcher.Sample>> e : gallery.entrySet()) {
            for (FaceMatcher.Sample s : e.getValue()) {
                probeOwners.add(e.getKey());
                probes.add(s);
            }
        }
        int step = maxProbes > 0 && probes.size() > maxProbes ? (int) Math.ceil(probes.size() / (double) maxProbes) : 1;

        List<Double> genuine = new ArrayList<>();
        List<Double> impostor = new ArrayList<>();
        int probesUsed = 0;
        for (int i = 0; i < probes.size(); i += step) {
            Long owner = probeOwners.get(i);
            FaceMatcher.Sample probe = probes.get(i);
            probesUsed++;
            double bestOther = Double.NEGATIVE_INFINITY;
            double bestOwn = Double.NEGATIVE_INFINITY;
            for (Map.Entry<Long, List<FaceMatcher.Sample>> e : gallery.entrySet()) {
                boolean own = e.getKey().equals(owner);
                for (FaceMatcher.Sample s : e.getValue()) {
                    if (own && s == probe) {
                        continue;
                    }
                    double score = FaceMatcher.cosine(probe.vector(), s.vector());
                    if (own) {
                        bestOwn = Math.max(bestOwn, score);
                    } else {
                        bestOther = Math.max(bestOther, score);
                    }
                }
            }
            if (bestOwn != Double.NEGATIVE_INFINITY) {
                genuine.add(bestOwn);
            }
            if (bestOther != Double.NEGATIVE_INFINITY) {
                impostor.add(bestOther);
            }
        }

        int confirmed = 0;
        int rejected = 0;
        List<ReviewPoint> safeReviews = reviews == null ? List.of() : reviews;
        for (ReviewPoint r : safeReviews) {
            if (r.genuine()) {
                genuine.add(r.score());
                confirmed++;
            } else {
                impostor.add(r.score());
                rejected++;
            }
        }

        double[] g = sorted(genuine);
        double[] im = sorted(impostor);

        List<Bin> histogram = new ArrayList<>();
        for (int b = 0; b < 20; b++) {
            double from = b * 0.05;
            double to = from + 0.05;
            histogram.add(new Bin(round2(from), round2(to), countIn(g, from, to, b == 19), countIn(im, from, to, b == 19)));
        }

        List<Row> table = new ArrayList<>();
        for (int t = 30; t <= 80; t += 5) {
            double th = t / 100.0;
            table.add(row(th, g, im, safeReviews));
        }

        Double recommendedHigh = null;
        if (im.length > 0) {
            double high = 0.90;
            for (int t = 30; t <= 90; t++) {
                if (shareAtOrAbove(im, t / 100.0) <= targetFar) {
                    high = t / 100.0;
                    break;
                }
            }
            if (im.length < Math.ceil(1.0 / Math.max(targetFar, 1e-6))) {
                // Not enough impostor scores to see a 1-in-(1/targetFar) tail: stay above all of them.
                high = Math.max(high, im[im.length - 1] + 0.03);
            }
            recommendedHigh = round2(clamp(high, 0.45, 0.85));
        }

        Double recommendedLow = null;
        if (g.length > 0) {
            double maxLow = (recommendedHigh != null ? recommendedHigh : currentHigh) - 0.05;
            double low = 0.25;
            for (int t = (int) Math.floor(maxLow * 100); t >= 25; t--) {
                if (1.0 - shareBelow(g, t / 100.0) >= targetRecall) {
                    low = t / 100.0;
                    break;
                }
            }
            recommendedLow = round2(clamp(low, 0.25, maxLow));
        }

        String confidence = (g.length >= 200 && im.length >= 1000) ? "HIGH"
                : (g.length >= 30 && im.length >= 200) ? "MEDIUM" : "LOW";

        List<String> notes = new ArrayList<>();
        if (several < 5) {
            notes.add("Few employees have more than one photo, so there is little same-person data. "
                    + "Add 2-3 extra photos for several employees, or confirm pending reviews, then run this again.");
        }
        if (confirmed + rejected < 20) {
            notes.add("Few review decisions yet. Decisions on real camera captures make this much more accurate; "
                    + "run it again after a few weeks of use.");
        }
        if (recommendedHigh != null && recommendedHigh > currentHigh + 0.02) {
            notes.add(String.format("The current high threshold %.2f is below the recommended %.2f: wrong people "
                    + "may be recorded automatically. Consider raising it.", currentHigh, recommendedHigh));
        } else if (recommendedHigh != null && recommendedHigh < currentHigh - 0.05) {
            notes.add(String.format("The high threshold could be lowered from %.2f to %.2f to record more "
                    + "attendance automatically with the same safety.", currentHigh, recommendedHigh));
        }
        if ("LOW".equals(confidence)) {
            notes.add("Confidence is LOW: treat the recommendations as a rough guide only.");
        }

        Row current = row(currentHigh, g, im, safeReviews);
        return new Report(employees, samples, several, probesUsed, confirmed, rejected, Stats.of(g), Stats.of(im),
                histogram, table, currentLow, currentHigh, current.far(), shareBelow(g, currentHigh),
                recommendedHigh, recommendedLow, confidence, notes);
    }

    private static Row row(double th, double[] g, double[] im, List<ReviewPoint> reviews) {
        int rejectedAbove = 0;
        int confirmedAbove = 0;
        for (ReviewPoint r : reviews) {
            if (r.score() >= th) {
                if (r.genuine()) {
                    confirmedAbove++;
                } else {
                    rejectedAbove++;
                }
            }
        }
        return new Row(round2(th), shareAtOrAbove(im, th), shareBelow(g, th), rejectedAbove, confirmedAbove);
    }

    static double shareAtOrAbove(double[] sorted, double t) {
        if (sorted.length == 0) {
            return 0.0;
        }
        int idx = lowerBound(sorted, t);
        return (sorted.length - idx) / (double) sorted.length;
    }

    static double shareBelow(double[] sorted, double t) {
        if (sorted.length == 0) {
            return 0.0;
        }
        return lowerBound(sorted, t) / (double) sorted.length;
    }

    /** First index with value >= t. */
    private static int lowerBound(double[] sorted, double t) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int countIn(double[] sorted, double from, double to, boolean last) {
        int n = 0;
        for (double v : sorted) {
            if (v >= from && (v < to || (last && v <= to))) {
                n++;
            }
        }
        return n;
    }

    private static double[] sorted(List<Double> values) {
        double[] out = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(out);
        return out;
    }

    static double quantile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return 0.0;
        }
        double pos = q * (sorted.length - 1);
        int i = (int) Math.floor(pos);
        int j = Math.min(i + 1, sorted.length - 1);
        return sorted[i] + (sorted[j] - sorted[i]) * (pos - i);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
