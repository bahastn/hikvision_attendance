package com.attendance.service.insightface;

import java.util.List;

/**
 * Pure face-quality rules: decides whether a detected face is good enough to be used, either as a
 * reference sample (strict {@link Policy}) or as a probe to match (lenient policy), and gives it a
 * 0..1 quality score for display and ranking. No Spring dependencies.
 *
 * <p>Measurements the sidecar did not report (older sidecar) are treated as "unknown" and do not
 * cause a rejection; only the detector score is always available.
 */
public final class FaceQuality {

    private FaceQuality() {
    }

    /**
     * Acceptance thresholds.
     *
     * @param minDetScore  minimum detector confidence
     * @param minFaceSize  minimum shorter side of the face box, pixels
     * @param maxYaw       maximum absolute head turn, degrees
     * @param maxPitch     maximum absolute head tilt, degrees
     * @param minBlur      minimum sharpness score
     */
    public record Policy(double minDetScore, double minFaceSize, double maxYaw, double maxPitch, double minBlur) {
    }

    /** Machine-readable rejection reasons (the UI translates them). */
    public enum Issue {
        NO_FACE, MULTIPLE_FACES, LOW_DETECTION, FACE_TOO_SMALL, TURNED_AWAY, TILTED, BLURRY
    }

    /** Result of an assessment: ok or the first issue found, plus a 0..1 quality score. */
    public record Assessment(boolean ok, Issue issue, String detail, double score) {
        static Assessment pass(double score) {
            return new Assessment(true, null, null, score);
        }

        static Assessment fail(Issue issue, String detail, double score) {
            return new Assessment(false, issue, detail, score);
        }
    }

    /**
     * Pick the face to use from all faces found in an image: the largest face box (the person
     * closest to the camera) among faces whose detector score passes {@code minDetScore}; falls back
     * to the most confident face. Returns null for an empty list.
     */
    public static DetectedFace primaryFace(List<DetectedFace> faces, double minDetScore) {
        if (faces == null || faces.isEmpty()) {
            return null;
        }
        DetectedFace best = null;
        for (DetectedFace f : faces) {
            if (f.detScore() < minDetScore) {
                continue;
            }
            if (best == null || f.area() > best.area()) {
                best = f;
            }
        }
        if (best != null) {
            return best;
        }
        DetectedFace mostConfident = faces.get(0);
        for (DetectedFace f : faces) {
            if (f.detScore() > mostConfident.detScore()) {
                mostConfident = f;
            }
        }
        return mostConfident;
    }

    /**
     * Assess an enrollment photo: exactly one clear face is expected. A second detected face only
     * counts when it passes the detector threshold and is at least 40% as wide as the main one (small
     * faces in the background, e.g. on a poster, are ignored).
     */
    public static Assessment assessEnrollment(List<DetectedFace> faces, Policy policy) {
        if (faces == null || faces.isEmpty()) {
            return Assessment.fail(Issue.NO_FACE, "No face found in the photo", 0.0);
        }
        DetectedFace main = primaryFace(faces, policy.minDetScore());
        int significant = 0;
        double mainSide = side(main);
        for (DetectedFace f : faces) {
            if (f.detScore() >= policy.minDetScore() && (mainSide <= 0 || side(f) >= 0.4 * mainSide)) {
                significant++;
            }
        }
        if (significant > 1) {
            return Assessment.fail(Issue.MULTIPLE_FACES, significant + " faces found; use a photo with one person",
                    score(main, policy));
        }
        return assess(main, policy);
    }

    /** Assess a single face against a policy. */
    public static Assessment assess(DetectedFace face, Policy policy) {
        if (face == null || face.embedding() == null || face.embedding().length == 0) {
            return Assessment.fail(Issue.NO_FACE, "No face found", 0.0);
        }
        double score = score(face, policy);
        if (face.detScore() < policy.minDetScore()) {
            return Assessment.fail(Issue.LOW_DETECTION,
                    String.format("Face not clear enough (detection %.2f < %.2f)", face.detScore(), policy.minDetScore()),
                    score);
        }
        if (face.faceSize() != null && face.faceSize() < policy.minFaceSize()) {
            return Assessment.fail(Issue.FACE_TOO_SMALL,
                    String.format("Face too small (%.0f px < %.0f px)", face.faceSize(), policy.minFaceSize()), score);
        }
        if (face.yaw() != null && Math.abs(face.yaw()) > policy.maxYaw()) {
            return Assessment.fail(Issue.TURNED_AWAY,
                    String.format("Head turned too far (%.0f° > %.0f°)", Math.abs(face.yaw()), policy.maxYaw()), score);
        }
        if (face.pitch() != null && Math.abs(face.pitch()) > policy.maxPitch()) {
            return Assessment.fail(Issue.TILTED,
                    String.format("Head tilted too far (%.0f° > %.0f°)", Math.abs(face.pitch()), policy.maxPitch()), score);
        }
        if (face.blur() != null && face.blur() < policy.minBlur()) {
            return Assessment.fail(Issue.BLURRY,
                    String.format("Photo too blurry (sharpness %.1f < %.1f)", face.blur(), policy.minBlur()), score);
        }
        return Assessment.pass(score);
    }

    /**
     * 0..1 quality score: the mean of the available components (detector confidence, face size,
     * frontalness, sharpness), each scaled so that "comfortably above the policy minimum" is 1.
     */
    public static double score(DetectedFace face, Policy policy) {
        if (face == null) {
            return 0.0;
        }
        double sum = clamp01(face.detScore() / 0.9);
        int n = 1;
        if (face.faceSize() != null) {
            sum += clamp01(face.faceSize() / Math.max(1.0, policy.minFaceSize() * 1.5));
            n++;
        }
        if (face.yaw() != null) {
            double pitch = face.pitch() != null ? Math.abs(face.pitch()) : 0.0;
            double angle = Math.max(Math.abs(face.yaw()), pitch);
            sum += clamp01(1.0 - angle / 90.0);
            n++;
        }
        if (face.blur() != null) {
            sum += clamp01(face.blur() / Math.max(1.0, policy.minBlur() * 3.0));
            n++;
        }
        return sum / n;
    }

    private static double side(DetectedFace f) {
        if (f == null) {
            return 0.0;
        }
        if (f.faceSize() != null) {
            return f.faceSize();
        }
        return Math.sqrt(f.area());
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
