package com.attendance.service;

import com.attendance.dto.FaceEvent;
import com.attendance.entity.CameraConfig;
import com.attendance.repository.CameraConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls the camera's face-comparison history (POST /ISAPI/Intelligent/FDLib/FCSearch — the
 * endpoint behind the camera's "Face Picture Comparison Result" page) and feeds new matches
 * into the {@link FaceEventProcessor}. This firmware records matches on the camera but does
 * not push them on the alertStream, so polling is what makes them show up in the dashboard.
 *
 * Each poll asks for matches since (last poll − overlap); the per-person cooldown in
 * FaceEventProcessor absorbs the overlap, so a match is recorded at most once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FaceComparisonPoller {

    private final CameraConfigRepository cameraRepository;
    private final HikvisionIsapiClient isapiClient;
    private final FaceEventProcessor eventProcessor;

    @Value("${attendance.camera.fcsearch-enabled:true}")
    private boolean enabled;

    @Value("${attendance.camera.fcsearch-overlap-seconds:10}")
    private long overlapSeconds;

    /** Poll end time per camera. Starts at "now", so a restart does not replay old matches. */
    private final Map<Long, Instant> lastPollByCamera = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${attendance.camera.fcsearch-interval-ms:5000}",
            initialDelayString = "${attendance.camera.fcsearch-interval-ms:5000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        for (CameraConfig camera : cameraRepository.findByIsActiveTrue()) {
            try {
                pollCamera(camera);
            } catch (Exception e) {
                log.warn("Face-comparison poll failed for {}: {}", camera.getIpAddress(), e.getMessage());
                eventProcessor.markDisconnected(camera);
            }
        }
    }

    private void pollCamera(CameraConfig camera) {
        Instant now = Instant.now();
        Instant last = lastPollByCamera.computeIfAbsent(camera.getId(), id -> now);
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime from = LocalDateTime.ofInstant(last.minus(Duration.ofSeconds(overlapSeconds)), zone);
        LocalDateTime to = LocalDateTime.ofInstant(now, zone);

        List<HikvisionIsapiClient.FaceComparison> matches =
                isapiClient.searchFaceComparisons(camera, from, to, 30);

        long cutoff = last.minus(Duration.ofSeconds(overlapSeconds)).toEpochMilli();
        for (HikvisionIsapiClient.FaceComparison match : matches) {
            if (match.snapTimeMillis <= cutoff) {
                continue; // already handled in a previous poll
            }
            log.info("Face match on {}: {} ({}) {}%",
                    camera.getIpAddress(), match.name, match.certificateNumber, match.similarity);
            FaceEvent event = FaceEvent.builder()
                    .employeeNo(match.certificateNumber)
                    .personName(match.name)
                    .similarity(match.similarity)
                    .eventTimeMillis(match.snapTimeMillis)
                    .pictureUrl(match.facePicUrl)
                    .build();
            byte[] picture = match.facePicUrl != null
                    ? isapiClient.downloadPicture(camera, match.facePicUrl).orElse(null)
                    : null;
            eventProcessor.process(camera, event, picture);
        }

        lastPollByCamera.put(camera.getId(), now);
        eventProcessor.markConnected(camera);
    }
}
