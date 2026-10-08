package com.attendance.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeDTO {
    private Long id;
    private String employeeId;
    private String fullName;
    private String department;
    private String position;
    private String email;
    private String phone;
    private String faceTemplateId;
    private String faceImageUrl;
    private Boolean isActive;
    private Long groupId;
    private String groupName;
}
