package com.attendance.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftDTO {
    private Long id;
    private String name;
    private String description;
    private String startTime;
    private String endTime;
    private Integer lateThresholdMinutes;
    private Integer earlyLeaveThresholdMinutes;
    private String breakStart;
    private String breakEnd;
    private String workingDays;
    private Boolean isActive;
    private Integer groupCount;
}
