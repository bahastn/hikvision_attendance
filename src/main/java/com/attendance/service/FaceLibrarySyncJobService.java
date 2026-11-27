package com.attendance.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs camera face-library syncs in the background so the HTTP request returns immediately
 * instead of blocking for minutes on a large library. The page starts a job, gets a job id,
 * and polls {@link #getStatus(String)} for progress and the final counts.
 *
 * <p>Kept in its own bean (separate from {@link EnhancedCameraFaceLibraryService}) on purpose:
 * the worker thread calls the transactional sync methods through the Spring proxy, so each sync
 * still runs inside its own transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaceLibrarySyncJobService {

    private final EnhancedCameraFaceLibraryService enhancedService;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "face-lib-sync");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, JobStatus> jobs = new ConcurrentHashMap<>();

    public enum State { RUNNING, DONE, ERROR }

    /** Mutable status of one sync job; fields are volatile so the polling thread sees updates. */
    public static final class JobStatus {
        private final String id;
        private final String scope;
        private final LocalDateTime startedAt = LocalDateTime.now();
        private volatile LocalDateTime finishedAt;
        private volatile State state = State.RUNNING;
        private volatile int imported, linked, updated, removed, skipped;
        private volatile String error;
        private volatile List<String> messages = List.of();

        JobStatus(String id, String scope) {
            this.id = id;
            this.scope = scope;
        }

        public String getId() { return id; }
        public String getScope() { return scope; }
        public LocalDateTime getStartedAt() { return startedAt; }
        public LocalDateTime getFinishedAt() { return finishedAt; }
        public String getState() { return state.name(); }
        public boolean isRunning() { return state == State.RUNNING; }
        public int getImported() { return imported; }
        public int getLinked() { return linked; }
        public int getUpdated() { return updated; }
        public int getRemoved() { return removed; }
        public int getSkipped() { return skipped; }
        public String getError() { return error; }
        public List<String> getMessages() { return messages; }

        void add(EnhancedCameraFaceLibraryService.SyncResult r) {
            imported += r.imported();
            linked += r.linked();
            updated += r.updated();
            removed += r.removed();
            skipped += r.skipped();
        }
    }

    public String startCameraSync(Long cameraId, String user) {
        JobStatus job = newJob("camera:" + cameraId);
        submit(job, () -> {
            EnhancedCameraFaceLibraryService.SyncResult r = enhancedService.syncCameraFaceLibrary(cameraId, user);
            job.add(r);
            job.messages = capped(r.messages());
        });
        return job.getId();
    }

    public String startSelectiveSync(Long cameraId, List<String> pids,
                                     Map<String, String> pidToEmployeeIdMap, String user) {
        JobStatus job = newJob("camera:" + cameraId + ":selective");
        submit(job, () -> {
            EnhancedCameraFaceLibraryService.SyncResult r =
                    enhancedService.selectiveSync(cameraId, pids, pidToEmployeeIdMap, user);
            job.add(r);
            job.messages = capped(r.messages());
        });
        return job.getId();
    }

    public String startSyncAll(String user) {
        JobStatus job = newJob("all");
        submit(job, () -> {
            Map<String, EnhancedCameraFaceLibraryService.SyncResult> results = enhancedService.syncAllCameras(user);
            results.forEach((cameraName, r) -> {
                job.add(r);
                if (r.messages() != null && !r.messages().isEmpty()) {
                    log.debug("Sync-all {}: {}", cameraName, r.messages());
                }
            });
            job.messages = capped(results.entrySet().stream()
                    .map(e -> e.getKey() + ": imported " + e.getValue().imported()
                            + ", linked " + e.getValue().linked() + ", updated " + e.getValue().updated()
                            + ", removed " + e.getValue().removed() + ", skipped " + e.getValue().skipped())
                    .toList());
        });
        return job.getId();
    }

    public JobStatus getStatus(String jobId) {
        return jobs.get(jobId);
    }

    private JobStatus newJob(String scope) {
        cleanupOld();
        JobStatus job = new JobStatus(UUID.randomUUID().toString(), scope);
        jobs.put(job.getId(), job);
        return job;
    }

    private void submit(JobStatus job, Runnable work) {
        executor.submit(() -> {
            try {
                work.run();
                job.state = State.DONE;
            } catch (Exception e) {
                log.error("Face-library sync job {} failed", job.getId(), e);
                job.error = e.getMessage();
                job.state = State.ERROR;
            } finally {
                job.finishedAt = LocalDateTime.now();
            }
        });
    }

    private static List<String> capped(List<String> messages) {
        if (messages == null) return List.of();
        int max = 100;
        return messages.size() <= max ? List.copyOf(messages) : List.copyOf(messages.subList(0, max));
    }

    /** Drop finished jobs older than an hour so the map does not grow without bound. */
    private void cleanupOld() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        jobs.values().removeIf(j -> j.finishedAt != null && j.finishedAt.isBefore(cutoff));
    }
}
