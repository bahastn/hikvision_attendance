package com.attendance.dto;

import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeGroupDTO {
    private Long id;
    private String name;
    private String description;
    private Long shiftId;
    private String shiftName;
    private Long shiftRuleId;
    private String shiftRuleName;
    private Boolean isActive;
    private Integer employeeCount;
    private List<Long> employeeIds;
}
