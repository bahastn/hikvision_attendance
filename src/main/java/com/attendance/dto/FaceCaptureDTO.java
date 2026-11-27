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

    /** Recognizer that looked at this capture: CAMERA or INSIGHTFACE. */
    private String recognitionSource;
    /** For an uncertain InsightFace suggestion awaiting review: the suggested employee + score. */
    private String candidateEmployeeId;
    private String candidateEmployeeName;
    private Double candidateScore;
    /** Why the capture is waiting for review (shown to the reviewer). */
    private String matchNote;
    /** Review state: NONE / PENDING_REVIEW / CONFIRMED / REJECTED. */
    private String reviewStatus;
}
