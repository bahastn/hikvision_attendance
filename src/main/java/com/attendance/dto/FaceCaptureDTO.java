package com.attendance.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FaceCaptureDTO {
    private Long id;
    private Long cameraId;
    private String cameraName;
    private String imageUrl;
    private String capturedAt;
    private String expiresAt;
    /** Employee ID the camera matched (certificateNumber / employee ID), when known. */
    private String matchedEmployeeId;
    private String matchedEmployeeName;
    /** Person name reported by the camera event. */
    private String personName;
    private Double similarity;
    /** True when the capture matched a known employee. */
    private boolean matched;
}
