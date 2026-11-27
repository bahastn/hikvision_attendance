package com.attendance.dto;

import lombok.*;
import java.time.LocalDateTime;

/**
 * DTO for sync history records.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncHistoryDTO {
    private Long id;
    private LocalDateTime syncTime;
    private Long cameraId;
    private String cameraName;
    private String operation;
    private Long employeeId;
    private String employeeNumber;
    private String employeeName;
    private String cameraPid;
    private String previousValue;
    private String newValue;
    private String performedBy;
    private String status;
    private String errorMessage;
    private String syncBatchId;
}
