package com.attendance.dto;

import lombok.*;

/**
 * A single face event parsed from a Hikvision ISAPI response
 * (AcsEvent or FaceCaptureRecord).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FaceEvent {
    /** Employee / person number reported by the camera (employeeNo). */
    private String employeeNo;
    /** Name reported by the camera (may be empty). */
    private String personName;
    /** Similarity percentage reported by the camera (0-100). */
    private Double similarity;
    /** Event time as epoch millis from the camera. */
    private Long eventTimeMillis;
    /** Raw picture URL path on the camera (to be downloaded). */
    private String pictureUrl;
    /**
     * Optional second picture of the same event (usually the wider scene / full-resolution snapshot).
     * InsightFace uses it when {@link #pictureUrl} gives no usable face (too small, blurred, or the
     * camera reported its library photo instead of the live capture).
     */
    private String backgroundPictureUrl;
    /** Serial number of the reporting device. */
    private String deviceId;
}
