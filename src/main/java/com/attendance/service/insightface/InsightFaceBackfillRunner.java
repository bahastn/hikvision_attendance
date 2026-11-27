package com.attendance.service.insightface;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Kicks off the InsightFace embedding backfill at startup when enabled. Kept separate from
 * {@link InsightFaceBackfillService} so the {@code @Async} service stays a plain CGLIB-proxied bean
 * (injectable by its concrete type), while this runner is the interface bean Spring Boot invokes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InsightFaceBackfillRunner implements ApplicationRunner {

    private final InsightFaceService insightFaceService;
    private final InsightFaceBackfillService backfillService;

    @Value("${insightface.backfill-on-startup:false}")
    private boolean backfillOnStartup;

    @Override
    public void run(ApplicationArguments args) {
        if (!insightFaceService.isEnabled() || !backfillOnStartup) {
            return;
        }
        log.info("InsightFace backfill-on-startup enabled; generating missing embeddings in background");
        backfillService.backfillAsync();
    }
}
