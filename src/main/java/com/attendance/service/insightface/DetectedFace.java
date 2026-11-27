package com.attendance.service.insightface;

/**
 * One face found by the InsightFace sidecar in an image: its embedding plus the quality measurements
 * the sidecar reports. The quality fields may be null when talking to an older sidecar (v1.0) that
 * only returned the embedding and detector score.
 *
 * @param embedding L2-normalized embedding (512 floats for buffalo_l)
 * @param detScore  detector confidence 0..1
 * @param bbox      face box [x1, y1, x2, y2] in image pixels, or null
 * @param faceSize  shorter side of the face box in pixels, or null
 * @param yaw       head turn left/right in degrees (0 = frontal), or null
 * @param pitch     head tilt up/down in degrees, or null
 * @param blur      sharpness (Laplacian variance of the face at 112x112; higher = sharper), or null
 */
public record DetectedFace(float[] embedding, double detScore, float[] bbox, Double faceSize,
                           Double yaw, Double pitch, Double blur) {

    /** A face with only embedding and detector score (no quality data). */
    public DetectedFace(float[] embedding, double detScore) {
        this(embedding, detScore, null, null, null, null, null);
    }

    /** Area of the face box in pixels (0 when unknown); used to pick the main face in a wide shot. */
    public double area() {
        if (bbox == null || bbox.length < 4) {
            return faceSize != null ? faceSize * faceSize : 0.0;
        }
        return Math.max(0.0, bbox[2] - bbox[0]) * Math.max(0.0, bbox[3] - bbox[1]);
    }
}
