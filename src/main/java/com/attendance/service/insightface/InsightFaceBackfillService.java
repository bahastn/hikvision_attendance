package com.attendance.service.insightface;

import com.attendance.entity.Employee;
import com.attendance.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Generates InsightFace embeddings for employees that already have an enrolled photo on disk but no
 * (or an outdated) embedding — e.g. employees enrolled before this feature existed, or enrolled
 * while the sidecar was down.
 *
 * <p>Triggered at startup by {@link InsightFaceBackfillRunner} (when
 * {@code insightface.backfill-on-startup=true}) and on demand from the admin endpoint. Idempotent
 * and best-effort: failures are logged, never thrown, and never block startup (the work runs
 * asynchronously). Mirrors the startup-runner pattern of {@code LeaveTypeDataInitializer}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InsightFaceBackfillService {

    private final InsightFaceService insightFaceService;
    private final InsightFaceEnrollmentListener photoReader;
    private final EmployeeRepository employeeRepository;
    private final FaceSampleService sampleService;

    /** Run the backfill off the calling thread (startup or the admin endpoint). */
    @Async
    public void backfillAsync() {
        try {
            int created = backfill();
            log.info("InsightFace backfill finished: {} embedding(s) created", created);
        } catch (Exception e) {
            log.warn("InsightFace backfill failed: {}", e.getMessage());
        }
    }

    /**
     * Create enrollment samples for active employees that have an enrolled photo but no sample yet.
     * Returns the number created. Safe to run repeatedly.
     */
    public int backfill() {
        if (!insightFaceService.isEnabled()) {
            return 0;
        }
        List<Employee> employees = employeeRepository.findByIsActiveTrue();
        int created = 0;
        for (Employee employee : employees) {
            if (employee.getId() == null) {
                continue;
            }
            if (sampleService.hasEnrolledSample(employee.getId())) {
                continue; // already has an enrollment sample
            }
            byte[] jpeg = photoReader.readEnrolledPhoto(employee.getId());
            if (jpeg == null) {
                continue; // no photo on disk to embed
            }
            if (insightFaceService.embedAndStore(employee, jpeg)) {
                created++;
            }
        }
        return created;
    }
}
