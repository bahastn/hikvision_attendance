package com.attendance.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for face match records with camera and processing info.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FaceMatchRecordDTO {
    private Long id;
    private String cameraPid;
    private String certificateNumber;
    private String personName;
    private Double similarity;
    private LocalDateTime matchTime;
    private Long cameraId;
    private String cameraName;
    private String facePicUrl;
    private String snapPicUrl;
    private String status;
    private String processedBy;
    private LocalDateTime processedAt;
    private Long employeeId;
    private String employeeName;
    private boolean hasPhoto;
}
