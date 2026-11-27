package com.attendance.dto;

import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaveDTO {
    private Long id;
    private Long employeeDbId;
    /** Employee group, used for access filtering (null = no group). */
    private Long groupId;
    private String employeeId;
    private String employeeName;
    private String department;
    /** Input: the chosen leave type id. */
    private Long leaveTypeId;
    /** Output: stable code of the leave type. */
    private String leaveTypeCode;
    /** Output: localized leave type label for the current locale. */
    private String leaveTypeName;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer totalDays;
    private String reason;
    /** Output: URL to stream the attachment ({@code /api/leaves/{id}/attachment}), null when none. */
    private String attachmentUrl;
    /** Output: original uploaded filename, for display/download. */
    private String attachmentFilename;
    /** Output: attachment MIME type, so the UI can decide image preview vs. download link. */
    private String attachmentContentType;
    private String status;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime createdAt;
}
