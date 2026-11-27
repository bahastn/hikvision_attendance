package com.attendance.service;

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
 *   <li>Everything is in <em>camera</em> time ({@link CameraClockService}), so a camera clock that is
 *       off, or in another time zone, does not make fresh matches look "old".</li>
 *   <li>Each match is processed exactly once and retried on failure ({@link MatchIngestService}).</li>
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
    private final MatchIngestService matchIngestService;
    private final CameraClockService clockService;

    @Value("${attendance.camera.fcsearch-enabled:true}")
    private boolean enabled;

    /** How far back each poll re-reads (camera time); generous because duplicates are free. */
    @Value("${attendance.camera.fcsearch-overlap-seconds:120}")
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
        // Work in CAMERA time: match times (snapTime) are stamped by the camera clock, which may be
        // behind/ahead of the server or in another time zone. Using the server clock here used to
        // put the cursor "after" fresh matches so they were skipped forever.
        LocalDateTime cameraNow = clockService.cameraNow(camera);

        MatchWindow.Window window = MatchWindow.window(camera.getLastMatchTime(), cameraNow, overlapSeconds,
                firstRunLookbackMinutes, maxCatchupHours);
        if (window.cursorReset()) {
            log.info("Match cursor of {} ({}) was ahead of the camera clock ({}); reset it",
                    camera.getCameraName(), camera.getLastMatchTime(), cameraNow);
        }
        LocalDateTime from = window.from();
        LocalDateTime to = window.to();

        List<HikvisionIsapiClient.FaceComparison> matches =
                isapiClient.searchFaceComparisons(camera, from, to, maxResults);
        MatchIngestService.Result result = matchIngestService.ingest(camera, matches);
        if (result.processed() > 0 || result.failed() > 0) {
            log.debug("FCSearch {}: {} new, {} already processed, {} failed", camera.getCameraName(),
                    result.processed(), result.alreadyDone(), result.failed());
        }

        LocalDateTime newCursor = MatchWindow.nextCursor(cameraNow, overlapSeconds,
                result.newest() != null ? LocalDateTime.ofInstant(Instant.ofEpochMilli(result.newest()), zone) : null,
                result.oldestFailed() != null ? LocalDateTime.ofInstant(Instant.ofEpochMilli(result.oldestFailed()), zone) : null);
        if (camera.getLastMatchTime() == null || !newCursor.equals(camera.getLastMatchTime())) {
            camera.setLastMatchTime(newCursor);
            cameraRepository.save(camera);
        }

        eventProcessor.markConnected(camera);
    }
}
