package com.attendance.dto;

import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncMessage {
    private String type;
    private String employeeId;
    private String employeeName;
    private String status;
    private LocalDateTime timestamp;
    private String deviceId;
    private Double similarityScore;
    private String direction;
    private String faceImageUrl;
    private String employeePhotoUrl;
    private Integer totalPresent;
    private Integer totalAbsent;
    private Integer totalLate;
}
