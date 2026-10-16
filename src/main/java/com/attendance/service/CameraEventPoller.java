package com.attendance.service;

import com.attendance.dto.FaceEvent;
import com.attendance.entity.CameraConfig;
import com.attendance.repository.CameraConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fallback polling mode for face events. Disabled while the real-time stream
 * watcher is active ({@code attendance.camera.stream-enabled=true}, the default):
 * the watcher keeps a permanent alertStream connection per camera, so polling
 * would only duplicate work.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CameraEventPoller {

    private final CameraConfigRepository cameraRepository;
    private final HikvisionIsapiClient isapiClient;
    private final FaceEventProcessor eventProcessor;

    @Value("${attendance.initial-lookback-minutes:5}")
    private int initialLookbackMinutes;

    @Value("${attendance.camera.stream-enabled:true}")
    private boolean streamEnabled;

    /** Poll all active cameras every 2 seconds (aligned with the UI sync interval). */
    @Scheduled(fixedRateString = "${attendance.camera.poll-interval:2000}")
    public void pollCameras() {
        if (streamEnabled) {
            return;
        }
        List<CameraConfig> cameras = cameraRepository.findByIsActiveTrue();
        for (CameraConfig camera : cameras) {
            try {
                pollCamera(camera);
            } catch (Exception e) {
                log.warn("Error polling camera {}: {}", camera.getIpAddress(), e.getMessage());
                eventProcessor.markDisconnected(camera);
            }
        }
    }

    private void pollCamera(CameraConfig camera) {
        if (!isapiClient.isReachable(camera)) {
            eventProcessor.markDisconnected(camera);
            return;
        }
        eventProcessor.markConnected(camera);

        List<FaceEvent> events = isapiClient.fetchNewFaceEvents(camera, initialLookbackMinutes);
        if (events.isEmpty()) {
            return;
        }

        log.info("Camera {} returned {} face event(s)", camera.getIpAddress(), events.size());

        for (FaceEvent event : events) {
            try {
                eventProcessor.process(camera, event, null);
            } catch (Exception e) {
                log.warn("Failed to handle face event from {}: {}", camera.getIpAddress(), e.getMessage());
            }
        }
    }
}
