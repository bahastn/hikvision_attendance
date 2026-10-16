package com.attendance.websocket;

import com.attendance.dto.SyncMessage;
import com.attendance.entity.AttendanceRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceWebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcastAttendanceUpdate(SyncMessage message) {
        messagingTemplate.convertAndSend("/topic/attendance", message);
        log.debug("Broadcasted attendance update: {}", message);
    }

    public void broadcastStatsUpdate(int present, int absent, int late) {
        SyncMessage stats = SyncMessage.builder()
                .type("STATS_UPDATE")
                .totalPresent(present)
                .totalAbsent(absent)
                .totalLate(late)
                .timestamp(java.time.LocalDateTime.now())
                .build();
        messagingTemplate.convertAndSend("/topic/stats", stats);
    }

    public void broadcastCameraStatus(String cameraId, boolean connected) {
        SyncMessage status = SyncMessage.builder()
                .type("CAMERA_STATUS")
                .deviceId(cameraId)
                .status(connected ? "ONLINE" : "OFFLINE")
                .timestamp(java.time.LocalDateTime.now())
                .build();
        messagingTemplate.convertAndSend("/topic/camera", status);
    }

    public void notifyNewDetection(AttendanceRecord record) {
        SyncMessage message = SyncMessage.builder()
                .type("NEW_DETECTION")
                .employeeId(record.getEmployee().getEmployeeId())
                .employeeName(record.getEmployee().getFullName())
                .status(record.getStatus().name())
                .timestamp(record.getCheckIn())
                .deviceId(record.getDeviceId())
                .similarityScore(record.getSimilarityScore())
                .direction(record.getDirection())
                .faceImageUrl(record.getFaceImageUrl())
                .employeePhotoUrl(record.getEmployee().getFaceImageUrl())
                .build();
        messagingTemplate.convertAndSend("/topic/detection", message);
    }
}
