package com.attendance.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Preview of what will happen during a camera face library sync.
 * Allows users to review and selectively import before committing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportPreview {
    private Long cameraId;
    private String cameraName;
    private LocalDateTime previewTime;
    
    private List<CameraFacePersonDTO> newPeople;
    private List<CameraFacePersonDTO> existingPeople;
    private List<CameraFacePersonDTO> conflicts;
    private List<CameraFacePersonDTO> deactivated;
    private List<CameraFacePersonDTO> noCertificate;
    
    private int totalOnCamera;
    private int totalNew;
    private int totalExisting;
    private int totalConflicts;
    private int totalDeactivated;
    private int totalNoCertificate;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CameraFacePersonDTO {
        private String pid;
        private String name;
        private String certificateNumber;
        private boolean inDatabase;
        private Long employeeDbId;
        private String employeeName;
        private boolean employeeActive;
        private String conflictReason;
        private String suggestedAction;
        private boolean selected; // For UI checkbox state
    }
}
