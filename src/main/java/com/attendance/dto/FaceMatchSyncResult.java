package com.attendance.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Result of a face match download/sync operation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FaceMatchSyncResult {
    private int totalMatches;
    private int newEmployees;
    private int linkedEmployees;
    private int skipped;
    private int errors;
    private List<String> messages;
    private String syncBatchId;
    private LocalDateTime syncTime;
    
    // For download
    private byte[] exportData;
    private String exportFilename;
    private String exportContentType;
}
