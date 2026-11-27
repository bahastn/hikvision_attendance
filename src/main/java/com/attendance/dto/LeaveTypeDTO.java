package com.attendance.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaveTypeDTO {
    private Long id;
    private String code;
    private String nameEn;
    private String nameKu;
    /** Localized label for the current request locale (output only). */
    private String name;
    private Integer displayOrder;
    private Boolean isActive;
    /** Number of leave records using this type (output only). */
    private Long usageCount;
}
