package com.attendance.dto;

import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceDTO {
    private Long id;
    /** Database id of the employee. */
    private Long employeeDbId;
    /** Employee group, used for access filtering (null = no group). */
    private Long groupId;
    private String employeeId;
    private String employeeName;
    private String department;
    private LocalDate attendanceDate;
    private LocalDateTime checkIn;
    private LocalDateTime checkOut;
    private Double workHours;
    private String status;
    private String deviceId;
    private Double similarityScore;
    private String faceImageUrl;
    private String employeePhotoUrl;
    private String direction;
    private String notes;
}
