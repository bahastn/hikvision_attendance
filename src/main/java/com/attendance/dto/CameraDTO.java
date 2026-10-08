package com.attendance.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CameraDTO {
    private Long id;
    private String cameraName;
    private String ipAddress;
    private Integer port;
    private String username;
    private String password;
    private String protocol;
    private Integer sdkPort;
    private Integer websocketPort;
    private Integer rtspPort;
    private Boolean isActive;
    private Boolean isConnected;
    private String location;
    private String lastSync;
}
