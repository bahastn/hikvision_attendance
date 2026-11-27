package com.attendance.service.insightface;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * At startup: migrates old single embeddings into face samples, then (when enabled) kicks off the
 * enrollment-sample backfill. Kept separate from
 * {@link InsightFaceBackfillService} so the {@code @Async} service stays a plain CGLIB-proxied bean
 * (injectable by its concrete type), while this runner is the interface bean Spring Boot invokes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InsightFaceBackfillRunner implements ApplicationRunner {

    private final InsightFaceService insightFaceService;
    private final InsightFaceBackfillService backfillService;
    private final FaceSampleService sampleService;
    private final InsightFaceGallery gallery;

    @Value("${insightface.backfill-on-startup:false}")
    private boolean backfillOnStartup;

    @Override
    public void run(ApplicationArguments args) {
        if (!insightFaceService.isEnabled()) {
            return;
        }
        // Move embeddings from the old one-per-employee table into face samples (no-op once done).
        try {
            if (sampleService.migrateLegacyEmbeddings() > 0) {
                gallery.reloadAll();
            }
        } catch (Exception e) {
            log.warn("Migrating legacy InsightFace embeddings failed: {}", e.getMessage());
        }
        if (!backfillOnStartup) {
            return;
        }
        log.info("InsightFace backfill-on-startup enabled; generating missing embeddings in background");
        backfillService.backfillAsync();
    }
}
