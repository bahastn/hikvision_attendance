package com.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One reference face sample of an employee, as shown in the "Face photos" dialog. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaceSampleDTO {
    private Long id;
    private Long employeeId;
    /** ENROLLED, UPLOADED, REVIEW_CONFIRMED or AUTO_LEARNED. */
    private String source;
    /** True for the enrollment photo that is also on the cameras (cannot be deleted here). */
    private boolean primary;
    private String imageUrl;
    private Double qualityScore;
    private Double detScore;
    private Double faceSize;
    private Double yaw;
    private Double pitch;
    private Double blur;
    private Double matchScore;
    private String model;
    private String createdBy;
    private String createdAt;
}
