package com.attendance.websocket;

import com.attendance.dto.SyncMessage;
import com.attendance.entity.AttendanceRecord;
import com.attendance.entity.Employee;
import com.attendance.security.AppModule;
import com.attendance.security.GroupScope;
import com.attendance.security.PermissionAction;
import com.attendance.security.PermissionResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Pushes live updates to signed-in browsers.
 * <p>
 * Messages go to each user's private queue ({@code /user/queue/...}) and only to
 * users whose permissions cover the employee's group, so a group-limited user
 * never receives names or photos from other groups.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceWebSocketService {

    private final SimpMessagingTemplate messagingTemplate;
    private final SimpUserRegistry userRegistry;
    private final PermissionResolver permissionResolver;

    /**
     * Per-group counters for today: index 0 = present, 1 = absent, 2 = late.
     * The map key is the employee group id (null = employees without a group).
     */
    public void broadcastStatsUpdate(Map<Long, int[]> countsByGroup) {
        for (SimpUser user : userRegistry.getUsers()) {
            GroupScope scope = scopeOf(user, AppModule.ATTENDANCE);
            if (scope.isNone()) {
                continue;
            }
            int present = 0, absent = 0, late = 0;
            for (Map.Entry<Long, int[]> e : countsByGroup.entrySet()) {
                if (scope.allows(e.getKey())) {
                    present += e.getValue()[0];
                    absent += e.getValue()[1];
                    late += e.getValue()[2];
                }
            }
            SyncMessage stats = SyncMessage.builder()
                    .type("STATS_UPDATE")
                    .totalPresent(present)
                    .totalAbsent(absent)
                    .totalLate(late)
                    .timestamp(java.time.LocalDateTime.now())
                    .build();
            messagingTemplate.convertAndSendToUser(user.getName(), "/queue/stats", stats);
        }
    }

    public void broadcastCameraStatus(String cameraId, boolean connected) {
        SyncMessage status = SyncMessage.builder()
                .type("CAMERA_STATUS")
                .deviceId(cameraId)
                .status(connected ? "ONLINE" : "OFFLINE")
                .timestamp(java.time.LocalDateTime.now())
                .build();
        for (SimpUser user : userRegistry.getUsers()) {
            if (!scopeOf(user, AppModule.CAMERAS).isNone()) {
                messagingTemplate.convertAndSendToUser(user.getName(), "/queue/camera", status);
            }
        }
    }

    /** Must be called inside a transaction (reads the employee's group). */
    public void notifyNewDetection(AttendanceRecord record) {
        Employee employee = record.getEmployee();
        Long groupId = employee.getGroup() != null ? employee.getGroup().getId() : null;
        SyncMessage message = SyncMessage.builder()
                .type("NEW_DETECTION")
                .employeeId(employee.getEmployeeId())
                .employeeName(employee.getFullName())
                .status(record.getStatus().name())
                .timestamp(record.getCheckIn())
                .deviceId(record.getDeviceId())
                .similarityScore(record.getSimilarityScore())
                .direction(record.getDirection())
                .faceImageUrl(record.getFaceImageUrl())
                .employeePhotoUrl(employee.getFaceImageUrl())
                .build();
        int recipients = 0;
        for (SimpUser user : userRegistry.getUsers()) {
            if (scopeOf(user, AppModule.ATTENDANCE).allows(groupId)) {
                messagingTemplate.convertAndSendToUser(user.getName(), "/queue/detection", message);
                recipients++;
            }
        }
        log.debug("Detection of {} sent to {} user(s)", employee.getEmployeeId(), recipients);
    }

    private GroupScope scopeOf(SimpUser user, AppModule module) {
        return permissionResolver.resolve(user.getName())
                .map(p -> p.scope(module, PermissionAction.VIEW))
                .orElse(GroupScope.NONE);
    }
}
