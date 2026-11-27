package com.attendance.service;

import com.attendance.entity.CameraConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knows how far each camera's clock is from the server clock.
 *
 * <p>Match times ({@code snapTime}) are stamped by the camera, so the match poller must reason in
 * camera time: a camera that is 40 seconds behind (no NTP) or set to another time zone would
 * otherwise have every match fall "before" a cursor computed from the server clock and be skipped.
 * The offset is measured with {@code GET /ISAPI/System/time} and refreshed periodically; when the
 * camera clock cannot be read the offset is assumed to be 0 (previous behaviour).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CameraClockService {

    private final HikvisionIsapiClient isapiClient;

    @Value("${attendance.camera.clock-refresh-minutes:10}")
    private long refreshMinutes;

    /** Log a warning when a camera clock is further off than this. */
    @Value("${attendance.camera.clock-warn-seconds:30}")
    private long warnSeconds;

    private record Offset(long millis, Instant measuredAt) {
    }

    private final Map<Long, Offset> offsets = new ConcurrentHashMap<>();

    /** Camera clock minus server clock, in milliseconds (0 when unknown). */
    public long offsetMillis(CameraConfig camera) {
        Offset cached = offsets.get(camera.getId());
        if (cached != null && cached.measuredAt().isAfter(Instant.now().minus(Duration.ofMinutes(refreshMinutes)))) {
            return cached.millis();
        }
        Optional<Long> measured = measure(camera);
        if (measured.isEmpty()) {
            // Keep the last known offset if there is one; otherwise assume the clocks agree.
            long fallback = cached != null ? cached.millis() : 0L;
            offsets.put(camera.getId(), new Offset(fallback, Instant.now()));
            return fallback;
        }
        long offset = measured.get();
        if (Math.abs(offset) > warnSeconds * 1000L
                && (cached == null || Math.abs(cached.millis() - offset) > 5000)) {
            log.warn("Camera {} ({}) clock is {} seconds {} the server. Matches are handled in camera time, "
                            + "but please enable NTP on the camera so reports show the right times.",
                    camera.getCameraName(), camera.getIpAddress(), Math.abs(offset) / 1000,
                    offset < 0 ? "behind" : "ahead of");
        }
        offsets.put(camera.getId(), new Offset(offset, Instant.now()));
        return offset;
    }

    /** "Now" on the camera's clock, as a local date-time in the server's zone. */
    public LocalDateTime cameraNow(CameraConfig camera) {
        long now = System.currentTimeMillis() + offsetMillis(camera);
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault());
    }

    /** Forget a camera's offset (e.g. after its configuration changed). */
    public void reset(Long cameraId) {
        offsets.remove(cameraId);
    }

    private Optional<Long> measure(CameraConfig camera) {
        long before = System.currentTimeMillis();
        Optional<Long> deviceTime = isapiClient.getDeviceTime(camera);
        long after = System.currentTimeMillis();
        // Compare with the middle of the request so network latency does not count as skew.
        return deviceTime.map(t -> t - (before + after) / 2);
    }
}
