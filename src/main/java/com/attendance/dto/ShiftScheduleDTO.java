package com.attendance.dto;

import lombok.*;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftScheduleDTO {
    private Long id;
    private Long groupId;
    private String groupName;
    private Long shiftId;
    private String shiftName;
    private String shiftStartTime;
    private String shiftEndTime;
    private Long ruleId;
    private String ruleName;
    private LocalDate startDate;
    private LocalDate endDate;
    private String notes;
    private Boolean isActive;
    private Boolean currentlyActive;
    private Integer employeeCount;
}
