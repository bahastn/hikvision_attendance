package com.attendance.service.insightface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thin HTTP client for the InsightFace sidecar (see the {@code insightface-service/} folder).
 *
 * <p>Modeled on {@link com.attendance.service.CentralSyncService}: a JDK {@link HttpClient},
 * Jackson for JSON, a {@code @Value} feature toggle, and every call wrapped so a failure returns
 * an empty result instead of throwing. When {@code insightface.enabled=false} it short-circuits
 * and makes no network calls at all, so the rest of the app behaves exactly as before.
 *
 * <p>A lightweight scheduled {@code /health} probe keeps an {@code available} flag so that, when
 * the sidecar is known to be down, per-event {@link #embed} calls return immediately instead of
 * waiting for a connection timeout on every camera event.
 */
@Component
@Slf4j
public class InsightFaceClient {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${insightface.enabled:false}")
    private boolean enabled;

    @Value("${insightface.base-url:http://127.0.0.1:8099}")
    private String baseUrl;

    @Value("${insightface.timeout-ms:4000}")
    private long timeoutMs;

    /** Set by the health probe; starts optimistic so the first event can try even before a probe. */
    private volatile boolean available = true;

    public boolean isEnabled() {
        return enabled;
    }

    /** Last known health of the sidecar (only meaningful when {@link #isEnabled()}). */
    public boolean isAvailable() {
        return enabled && available;
    }

    /**
     * Detect faces in a JPEG and return their embeddings, most-confident first.
     * Returns an empty list when disabled, when the sidecar is unreachable, or when no face is
     * found — never throws.
     */
    public List<FaceEmbedding> embed(byte[] jpeg) {
        if (!enabled || jpeg == null || jpeg.length == 0) {
            return List.of();
        }
        if (!available) {
            // Known-down: skip the per-event connection timeout. The health probe will flip this
            // back as soon as the sidecar answers again.
            return List.of();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(baseUrl) + "/embed"))
                    .timeout(Duration.ofMillis(Math.max(500, timeoutMs)))
                    .header("Content-Type", "image/jpeg")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                log.warn("InsightFace /embed returned HTTP {}", code);
                return List.of();
            }
            return parse(response.body());
        } catch (Exception e) {
            // Mark unavailable so we don't pay the timeout on every subsequent event until the
            // next successful health probe.
            available = false;
            log.debug("InsightFace /embed failed, marking sidecar unavailable: {}", e.toString());
            return List.of();
        }
    }

    private List<FaceEmbedding> parse(byte[] body) throws Exception {
        JsonNode root = json.readTree(body);
        JsonNode faces = root.path("faces");
        if (!faces.isArray()) {
            return List.of();
        }
        List<FaceEmbedding> out = new ArrayList<>(faces.size());
        for (JsonNode face : faces) {
            JsonNode embNode = face.path("embedding");
            if (!embNode.isArray() || embNode.isEmpty()) {
                continue;
            }
            float[] vector = new float[embNode.size()];
            for (int i = 0; i < embNode.size(); i++) {
                vector[i] = (float) embNode.get(i).asDouble();
            }
            double detScore = face.path("det_score").asDouble(0.0);
            out.add(new FaceEmbedding(vector, detScore));
        }
        return out;
    }

    /** The single best (most confident) face in a JPEG, or empty when none. */
    public Optional<FaceEmbedding> embedBestFace(byte[] jpeg) {
        List<FaceEmbedding> faces = embed(jpeg);
        return faces.isEmpty() ? Optional.empty() : Optional.of(faces.get(0));
    }

    @Scheduled(fixedDelayString = "${insightface.health-check-interval-ms:30000}",
            initialDelayString = "${insightface.health-check-interval-ms:30000}")
    public void probeHealth() {
        if (!enabled) {
            return;
        }
        boolean ok = checkHealth();
        if (ok != available) {
            log.info("InsightFace sidecar is now {}", ok ? "available" : "unavailable");
        }
        available = ok;
    }

    /** One-shot health check against the sidecar. */
    public boolean checkHealth() {
        if (!enabled) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(baseUrl) + "/health"))
                    .timeout(Duration.ofMillis(Math.max(500, timeoutMs)))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://127.0.0.1:8099";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** One detected face: its (already L2-normalized) embedding and the detector's confidence. */
    public record FaceEmbedding(float[] embedding, double detScore) {
    }
}
