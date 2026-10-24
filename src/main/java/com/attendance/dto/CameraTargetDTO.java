package com.attendance.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CameraTargetDTO {
    private Long cameraId;
    private String cameraName;
    private String location;
    private Boolean enrolled;
    private String pid;
    private String enrolledAt;
}
