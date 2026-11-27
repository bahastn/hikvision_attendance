package com.attendance.service;

import com.attendance.entity.CameraConfig;
import com.attendance.entity.FaceMatchRecord;
import com.attendance.repository.FaceMatchRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pushes recorded face matches to a central server as lightweight records — only
 * employeeId, date, time and cameraId (plus name and similarity); never the image.
 *
 * <p>Outbox pattern: {@link FaceComparisonPoller} stores every match locally first
 * ({@link FaceMatchRecord}, state {@code PENDING}). This sender runs on a schedule, posts the
 * oldest pending/failed matches in batches, marks them {@code SENT} on an HTTP 2xx, and leaves
 * failures to be retried on the next run (up to {@code max-attempts}). Because persistence and
 * sending are decoupled, nothing is lost if the central server is briefly unreachable.
 *
 * <p>Disabled by default: it does nothing until {@code attendance.central-sync.enabled=true} and
 * {@code attendance.central-sync.url} are set, so the app runs unchanged until a central server
 * is configured.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentralSyncService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final FaceMatchRecordRepository matchRepository;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${attendance.central-sync.enabled:false}")
    private boolean enabled;

    @Value("${attendance.central-sync.url:}")
    private String url;

    /** Optional header for auth, e.g. "Authorization" or "X-API-Key". */
    @Value("${attendance.central-sync.auth-header:}")
    private String authHeader;

    /** Value for the auth header, e.g. "Bearer xxx". */
    @Value("${attendance.central-sync.auth-token:}")
    private String authToken;

    @Value("${attendance.central-sync.batch-size:200}")
    private int batchSize;

    @Value("${attendance.central-sync.max-attempts:20}")
    private int maxAttempts;

    @Value("${attendance.central-sync.request-timeout-seconds:20}")
    private long requestTimeoutSeconds;

    @Scheduled(fixedDelayString = "${attendance.central-sync.interval-ms:15000}",
            initialDelayString = "${attendance.central-sync.interval-ms:15000}")
    public void pushPendingMatches() {
        if (!enabled || url == null || url.isBlank()) {
            return;
        }

        List<Payload> batch = loadBatch();
        if (batch.isEmpty()) {
            return;
        }

        List<Long> ids = batch.stream().map(p -> p.matchId).toList();
        String error = postBatch(batch);
        if (error == null) {
            markSent(ids);
            log.info("Pushed {} match(es) to central server", ids.size());
        } else {
            markFailed(ids, error);
            log.warn("Central sync of {} match(es) failed: {}", ids.size(), error);
        }
    }

    /** Load the next batch and project the no-image fields (camera fetched eagerly by the query). */
    private List<Payload> loadBatch() {
        List<FaceMatchRecord> records = matchRepository.findPendingForSync(
                List.of(FaceMatchRecord.CentralSyncState.PENDING,
                        FaceMatchRecord.CentralSyncState.FAILED),
                maxAttempts,
                PageRequest.of(0, Math.max(1, batchSize)));

        List<Payload> out = new ArrayList<>(records.size());
        for (FaceMatchRecord r : records) {
            out.add(new Payload(r.getId(), r.getCertificateNumber(), r.getPersonName(),
                    cameraId(r.getCamera()),
                    r.getCamera() != null ? r.getCamera().getCameraName() : null,
                    r.getMatchTime(), r.getSimilarity()));
        }
        return out;
    }

    /** Stable camera id for the central server: external id when set, else the local DB id. */
    private String cameraId(CameraConfig camera) {
        if (camera == null) {
            return null;
        }
        if (camera.getExternalId() != null && !camera.getExternalId().isBlank()) {
            return camera.getExternalId().trim();
        }
        return String.valueOf(camera.getId());
    }

    /** POST the batch as a JSON array. Returns null on success, or an error description. */
    private String postBatch(List<Payload> batch) {
        try {
            List<Map<String, Object>> body = new ArrayList<>(batch.size());
            for (Payload p : batch) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("employeeId", p.employeeId);
                m.put("personName", p.personName);
                m.put("cameraId", p.cameraId);
                m.put("cameraName", p.cameraName);
                m.put("date", p.matchTime != null ? p.matchTime.format(DATE_FMT) : null);
                m.put("time", p.matchTime != null ? p.matchTime.format(TIME_FMT) : null);
                m.put("timestamp", p.matchTime != null ? p.matchTime.format(ISO_FMT) : null);
                m.put("similarity", p.similarity);
                body.add(m);
            }
            byte[] payload = json.writeValueAsBytes(body);

            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            if (authHeader != null && !authHeader.isBlank() && authToken != null && !authToken.isBlank()) {
                req.header(authHeader.trim(), authToken.trim());
            }

            HttpResponse<String> response = httpClient.send(req.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                return null;
            }
            String snippet = response.body() == null ? "" :
                    response.body().substring(0, Math.min(200, response.body().length()));
            return "HTTP " + code + (snippet.isBlank() ? "" : " — " + snippet);
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private void markSent(List<Long> ids) {
        List<FaceMatchRecord> records = matchRepository.findAllById(ids);
        LocalDateTime now = LocalDateTime.now();
        for (FaceMatchRecord r : records) {
            r.setCentralSyncState(FaceMatchRecord.CentralSyncState.SENT);
            r.setSyncedAt(now);
            r.setLastSyncAttempt(now);
            r.setSyncAttempts(r.getSyncAttempts() == null ? 1 : r.getSyncAttempts() + 1);
            r.setSyncError(null);
        }
        matchRepository.saveAll(records);
    }

    private void markFailed(List<Long> ids, String error) {
        List<FaceMatchRecord> records = matchRepository.findAllById(ids);
        LocalDateTime now = LocalDateTime.now();
        String trimmed = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        for (FaceMatchRecord r : records) {
            r.setCentralSyncState(FaceMatchRecord.CentralSyncState.FAILED);
            r.setLastSyncAttempt(now);
            r.setSyncAttempts(r.getSyncAttempts() == null ? 1 : r.getSyncAttempts() + 1);
            r.setSyncError(trimmed);
        }
        matchRepository.saveAll(records);
    }

    /** No-image projection of a match for the central push. */
    private static final class Payload {
        final Long matchId;
        final String employeeId;
        final String personName;
        final String cameraId;
        final String cameraName;
        final LocalDateTime matchTime;
        final Double similarity;

        Payload(Long matchId, String employeeId, String personName, String cameraId,
                String cameraName, LocalDateTime matchTime, Double similarity) {
            this.matchId = matchId;
            this.employeeId = employeeId;
            this.personName = personName;
            this.cameraId = cameraId;
            this.cameraName = cameraName;
            this.matchTime = matchTime;
            this.similarity = similarity;
        }
    }
}
