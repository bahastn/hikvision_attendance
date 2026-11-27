package com.attendance.service;

import com.attendance.dto.FaceEvent;
import com.attendance.entity.CameraConfig;
import com.attendance.entity.FaceMatchRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single path from "the camera recorded a face match" to attendance. Used by the 5-second
 * {@link FaceComparisonPoller} and by the catch-up {@link AttendanceSyncService}, so every match —
 * live or caught up later — gets the same employee resolution, InsightFace check, capture
 * library entry, attendance record and WebSocket notification.
 *
 * <p>Each match is first stored in the durable match log (unique per camera / person / second),
 * which also remembers whether it was already processed. A match is processed exactly once; a
 * failure is retried on the next poll (up to {@code attendance.camera.match-max-attempts}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchIngestService {

    private final FaceMatchService faceMatchService;
    private final FaceEventProcessor eventProcessor;
    private final HikvisionIsapiClient isapiClient;

    @Value("${attendance.camera.match-max-attempts:5}")
    private int maxAttempts;

    /**
     * @param processed     matches processed now
     * @param alreadyDone   matches that had been processed before (duplicates of the overlap window)
     * @param failed        matches whose processing failed this time (will be retried)
     * @param oldestFailed  camera time of the oldest failed match (millis), or null — the poller keeps
     *                      its cursor before it so the match is fetched again
     * @param newest        camera time of the newest match seen (millis), or null
     */
    public record Result(int processed, int alreadyDone, int failed, Long oldestFailed, Long newest,
                         List<String> messages) {
    }

    /** One lock per camera: the live poller and the catch-up sync never process a camera at once. */
    private final Map<Long, Object> cameraLocks = new ConcurrentHashMap<>();

    public Result ingest(CameraConfig camera, List<HikvisionIsapiClient.FaceComparison> matches) {
        synchronized (cameraLocks.computeIfAbsent(camera.getId(), id -> new Object())) {
            return ingestLocked(camera, matches);
        }
    }

    private Result ingestLocked(CameraConfig camera, List<HikvisionIsapiClient.FaceComparison> matches) {
        List<HikvisionIsapiClient.FaceComparison> ordered = new ArrayList<>(matches);
        // Oldest first, so check-in comes before check-out.
        ordered.sort(Comparator.comparingLong(m -> m.snapTimeMillis));

        ZoneId zone = ZoneId.systemDefault();
        int processed = 0;
        int alreadyDone = 0;
        int failed = 0;
        Long oldestFailed = null;
        Long newest = null;
        List<String> messages = new ArrayList<>();

        for (HikvisionIsapiClient.FaceComparison match : ordered) {
            newest = newest == null ? match.snapTimeMillis : Math.max(newest, match.snapTimeMillis);
            LocalDateTime matchTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(match.snapTimeMillis), zone);

            FaceMatchRecord record;
            try {
                // Durable raw-match log (idempotent) — every match, queued for central sync.
                record = faceMatchService.recordMatch(camera, match.pid, match.certificateNumber,
                        match.name, match.similarity, matchTime, match.facePicUrl, match.snapPicUrl);
            } catch (Exception e) {
                failed++;
                oldestFailed = oldestFailed == null ? match.snapTimeMillis : Math.min(oldestFailed, match.snapTimeMillis);
                log.warn("Could not store match of {} on {}: {}", match.name, camera.getIpAddress(), e.getMessage());
                continue;
            }
            if (record == null) {
                failed++;
                oldestFailed = oldestFailed == null ? match.snapTimeMillis : Math.min(oldestFailed, match.snapTimeMillis);
                continue;
            }
            if (!FaceMatchService.needsAttendanceProcessing(record)) {
                alreadyDone++;
                continue;
            }

            log.info("Face match on {}: {} ({}) {}% at {}", camera.getIpAddress(), match.name,
                    match.certificateNumber, match.similarity, matchTime);
            try {
                FaceEvent event = FaceEvent.builder()
                        .employeeNo(match.certificateNumber)
                        .pid(match.pid)
                        .personName(match.name)
                        .similarity(match.similarity)
                        .eventTimeMillis(match.snapTimeMillis)
                        .pictureUrl(match.facePicUrl)
                        // The other picture of the match; InsightFace falls back to it when the
                        // first one has no usable face.
                        .backgroundPictureUrl(match.snapPicUrl)
                        .build();
                byte[] picture = match.facePicUrl != null
                        ? isapiClient.downloadPicture(camera, match.facePicUrl).orElse(null)
                        : null;
                eventProcessor.process(camera, event, picture);
                faceMatchService.markAttendanceProcessed(record);
                processed++;
                messages.add("Processed " + match.name + " (" + match.certificateNumber + ") at " + matchTime);
            } catch (Exception e) {
                failed++;
                oldestFailed = oldestFailed == null ? match.snapTimeMillis : Math.min(oldestFailed, match.snapTimeMillis);
                messages.add("Failed " + match.name + " at " + matchTime + ": " + e.getMessage());
                log.warn("Processing match of {} on {} failed (will retry): {}", match.name,
                        camera.getIpAddress(), e.getMessage());
                try {
                    faceMatchService.markAttendanceFailed(record, e.getMessage(), maxAttempts);
                } catch (Exception inner) {
                    log.warn("Could not record the failure of match {}: {}", record.getMatchKey(), inner.getMessage());
                }
            }
        }
        return new Result(processed, alreadyDone, failed, oldestFailed, newest, messages);
    }
}
