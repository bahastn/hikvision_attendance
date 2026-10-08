package com.attendance.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftRuleDTO {
    private Long id;
    private String name;
    private String description;
    private Integer lateThresholdMinutes;
    private Integer earlyLeaveThresholdMinutes;
    private Integer absentAfterMinutes;
    private Boolean requireCheckIn;
    private Boolean requireCheckOut;
    private Integer gracePeriodMinutes;
    private Double maxWorkHours;
    private Boolean isActive;
    private Integer groupCount;
    private Integer scheduleCount;
}
