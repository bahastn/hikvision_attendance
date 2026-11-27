package com.attendance.service;

import com.attendance.dto.FaceEvent;
import com.attendance.entity.CameraConfig;
import com.attendance.repository.CameraConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Polls the camera's face-comparison history (POST /ISAPI/Intelligent/FDLib/FCSearch — the
 * endpoint behind the camera's "Face Picture Comparison Result" page) and feeds new matches
 * into the durable match log ({@link FaceMatchService#recordMatch}) and the attendance pipeline
 * ({@link FaceEventProcessor}). This firmware records matches on the camera but does not push
 * them on the alertStream, so polling is what makes them show up and get synced.
 *
 * <p><b>No match is lost:</b>
 * <ul>
 *   <li>The progress cursor ({@link CameraConfig#getLastMatchTime()}) is persisted in the DB, so a
 *       restart or downtime resumes from the last processed match instead of "now" — the gap is
 *       back-filled on the next poll (bounded by {@code fcsearch-max-catchup-hours}).</li>
 *   <li>The ISAPI client pages through the ENTIRE window, so bursts larger than one page are not
 *       dropped.</li>
 *   <li>Every raw match is stored under a unique key, so re-querying the overlap window never
 *       creates duplicates.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FaceComparisonPoller {

    private final CameraConfigRepository cameraRepository;
    private final HikvisionIsapiClient isapiClient;
    private final FaceEventProcessor eventProcessor;
    private final FaceMatchService faceMatchService;

    @Value("${attendance.camera.fcsearch-enabled:true}")
    private boolean enabled;

    @Value("${attendance.camera.fcsearch-overlap-seconds:10}")
    private long overlapSeconds;

    /** Max matches pulled from one camera in a single poll (across all pages). */
    @Value("${attendance.camera.fcsearch-max-results:2000}")
    private int maxResults;

    /** On first run (or after a long outage) never look further back than this. */
    @Value("${attendance.camera.fcsearch-max-catchup-hours:72}")
    private long maxCatchupHours;

    /** How far back the very first poll of a camera looks (no cursor yet). */
    @Value("${attendance.camera.fcsearch-first-run-lookback-minutes:60}")
    private long firstRunLookbackMinutes;

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
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now();

        LocalDateTime cursor = camera.getLastMatchTime();
        LocalDateTime from = cursor != null
                ? cursor.minusSeconds(overlapSeconds)
                : now.minusMinutes(firstRunLookbackMinutes);

        // Bound catch-up so a long outage (or an unset/ancient cursor) cannot trigger a
        // multi-month scan. The window older than this is covered by the nightly sync instead.
        LocalDateTime earliest = now.minusHours(maxCatchupHours);
        if (from.isBefore(earliest)) {
            from = earliest;
        }

        List<HikvisionIsapiClient.FaceComparison> matches =
                isapiClient.searchFaceComparisons(camera, from, now, maxResults);

        long cursorMillis = cursor != null ? cursor.atZone(zone).toInstant().toEpochMilli() : Long.MIN_VALUE;
        long maxProcessed = cursorMillis;

        for (HikvisionIsapiClient.FaceComparison match : matches) {
            if (match.snapTimeMillis <= cursorMillis) {
                continue; // already handled before the cursor; the overlap just guards clock skew
            }
            LocalDateTime matchTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(match.snapTimeMillis), zone);

            log.info("Face match on {}: {} ({}) {}%",
                    camera.getIpAddress(), match.name, match.certificateNumber, match.similarity);

            // 1) Durable raw-match log (idempotent) — every match, queued for central sync.
            faceMatchService.recordMatch(camera, match.pid, match.certificateNumber,
                    match.name, match.similarity, matchTime, match.facePicUrl, match.snapPicUrl);

            // 2) Attendance pipeline (its own per-person cooldown decides check-in/out).
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

            if (match.snapTimeMillis > maxProcessed) {
                maxProcessed = match.snapTimeMillis;
            }
        }

        // Advance the cursor: at least to "now - overlap" (so an idle camera doesn't re-scan a
        // growing window), but never past the newest match we actually processed, and never
        // backwards.
        LocalDateTime caughtUp = now.minusSeconds(overlapSeconds);
        LocalDateTime processedUpTo = maxProcessed > cursorMillis
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(maxProcessed), zone)
                : null;
        LocalDateTime newCursor = caughtUp;
        if (processedUpTo != null && processedUpTo.isAfter(newCursor)) {
            newCursor = processedUpTo;
        }
        if (cursor == null || newCursor.isAfter(cursor)) {
            camera.setLastMatchTime(newCursor);
            cameraRepository.save(camera);
        }

        eventProcessor.markConnected(camera);
    }
}
