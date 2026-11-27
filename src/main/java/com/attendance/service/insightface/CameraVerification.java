package com.attendance.service.insightface;

/**
 * Pure decision for phase 5: does InsightFace back up the employee the <em>camera</em> recognized?
 * No Spring dependencies.
 *
 * <ul>
 *   <li>{@link Outcome#CONFLICT}: another employee matches confidently (&ge; high) and clearly better
 *       than the camera's employee (by at least the margin) — possibly a camera mix-up.</li>
 *   <li>{@link Outcome#AGREE}: the face is consistent with the camera's employee (&ge; low).</li>
 *   <li>{@link Outcome#WEAK}: the face does not look like the camera's employee's photos (&lt; low),
 *       and nobody else matches confidently either.</li>
 *   <li>{@link Outcome#NOT_CHECKED}: no usable face, the employee has no photos for InsightFace, or
 *       verification is off/unavailable — the camera's decision stands.</li>
 * </ul>
 */
public final class CameraVerification {

    private CameraVerification() {
    }

    public enum Outcome { AGREE, WEAK, CONFLICT, NOT_CHECKED }

    /**
     * A probe that is at least this similar to one of the employee's stored photos is almost
     * certainly that stored photo itself (e.g. the camera reported its library picture instead of
     * the live capture), so it proves nothing about who stood in front of the camera.
     */
    public static final double STORED_PHOTO_SIMILARITY = 0.97;

    /**
     * @param cameraEmployeeScore InsightFace score of the probe against the camera's employee
     * @param bestOtherScore      best score against any other employee (0 when none)
     */
    public static Outcome decide(double cameraEmployeeScore, double bestOtherScore, double lowThreshold,
                                 double highThreshold, double minMargin) {
        if (bestOtherScore >= highThreshold && bestOtherScore - cameraEmployeeScore >= minMargin) {
            return Outcome.CONFLICT;
        }
        if (cameraEmployeeScore >= lowThreshold) {
            return Outcome.AGREE;
        }
        return Outcome.WEAK;
    }
}
