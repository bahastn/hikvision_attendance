package com.attendance.service;

import com.attendance.entity.CameraConfig;
import com.attendance.repository.CameraConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps one long-lived alertStream connection open per active camera (the same
 * mechanism as the proven curl watcher) and feeds every parsed event into the
 * {@link FaceEventProcessor}. Streams reconnect automatically with backoff and
 * are restarted when the camera configuration changes.
 *
 * This replaces periodic polling as the default mode; set
 * {@code attendance.camera.stream-enabled=false} to fall back to polling.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CameraEventWatcher implements SmartLifecycle {

    private final CameraConfigRepository cameraRepository;
    private final HikvisionIsapiClient isapiClient;
    private final FaceEventProcessor eventProcessor;

    @Value("${attendance.camera.stream-enabled:true}")
    private boolean streamEnabled;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "camera-stream-" + r.hashCode());
        t.setDaemon(true);
        return t;
    });

    private final Map<Long, StreamTask> streams = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    private record StreamTask(AtomicBoolean keepRunning, String configSignature) {
    }

    @Override
    public void start() {
        running = true;
        reconcile();
    }

    @Override
    public void stop() {
        running = false;
        streams.values().forEach(task -> task.keepRunning().set(false));
        streams.clear();
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Start streams for new/changed cameras and stop streams for removed/inactive ones. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void reconcile() {
        if (!running || !streamEnabled) {
            return;
        }
        List<CameraConfig> activeCameras = cameraRepository.findByIsActiveTrue();

        streams.entrySet().removeIf(entry -> {
            CameraConfig current = activeCameras.stream()
                    .filter(c -> c.getId().equals(entry.getKey()))
                    .findFirst()
                    .orElse(null);
            if (current == null || !signature(current).equals(entry.getValue().configSignature())) {
                log.info("Stopping event stream for camera id={} (removed, deactivated or reconfigured)",
                        entry.getKey());
                entry.getValue().keepRunning().set(false);
                return true;
            }
            return false;
        });

        for (CameraConfig camera : activeCameras) {
            if (!streams.containsKey(camera.getId())) {
                streams.put(camera.getId(), startStream(camera));
            }
        }
    }

    private StreamTask startStream(CameraConfig camera) {
        StreamTask task = new StreamTask(new AtomicBoolean(true), signature(camera));
        executor.submit(() -> {
            long backoffMs = 2000;
            while (task.keepRunning().get()) {
                try {
                    log.info("Opening alertStream on {}:{}", camera.getIpAddress(), camera.getPort());
                    isapiClient.readAlertStream(camera,
                            (event, picture) -> eventProcessor.process(camera, event, picture),
                            null,
                            task.keepRunning()::get);
                    backoffMs = 2000;
                } catch (Exception e) {
                    log.warn("alertStream on {} failed: {} — retrying in {}s",
                            camera.getIpAddress(), e.getMessage(), backoffMs / 1000);
                    eventProcessor.markDisconnected(camera);
                    backoffMs = Math.min(backoffMs * 2, 60_000);
                }
                if (!task.keepRunning().get()) {
                    break;
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.info("Event stream stopped for {}", camera.getIpAddress());
        });
        return task;
    }

    private String signature(CameraConfig c) {
        return c.getIpAddress() + ":" + c.getPort() + ":" + c.getProtocol()
                + ":" + c.getUsername() + ":" + c.getPassword();
    }
}
