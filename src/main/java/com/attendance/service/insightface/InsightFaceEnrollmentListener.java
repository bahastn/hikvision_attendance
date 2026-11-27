package com.attendance.service.insightface;

import com.attendance.entity.Employee;
import com.attendance.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Keeps InsightFace embeddings in sync with employee face enrollment. Runs after the enrollment
 * transaction commits and asynchronously, so generating the embedding (a sidecar call) never blocks
 * or breaks the enrollment itself — if the sidecar is down the embedding is simply skipped and the
 * backfill runner / admin endpoint can create it later.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InsightFaceEnrollmentListener {

    private final InsightFaceService insightFaceService;
    private final EmployeeRepository employeeRepository;

    @Value("${attendance.face-storage-dir:faces}")
    private String faceStorageDir;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFaceEnrolled(FaceEmbeddingEvents.FaceEnrolled event) {
        if (!insightFaceService.isEnabled()) {
            return;
        }
        try {
            Employee employee = employeeRepository.findById(event.employeeDbId()).orElse(null);
            if (employee == null) {
                return;
            }
            byte[] jpeg = readEnrolledPhoto(employee.getId());
            if (jpeg == null) {
                log.debug("No enrolled photo on disk for employee {} — skipping embedding",
                        employee.getEmployeeId());
                return;
            }
            insightFaceService.embedAndStore(employee, jpeg);
        } catch (Exception e) {
            log.warn("InsightFace embedding after enrollment failed for employee #{}: {}",
                    event.employeeDbId(), e.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFaceRemoved(FaceEmbeddingEvents.FaceRemoved event) {
        try {
            insightFaceService.removeEmbedding(event.employeeDbId());
        } catch (Exception e) {
            log.warn("Removing InsightFace embedding for employee #{} failed: {}",
                    event.employeeDbId(), e.getMessage());
        }
    }

    /** Read the employee's enrolled JPEG ({@code faces/enrolled/employee_{id}.jpg}), or null. */
    public byte[] readEnrolledPhoto(Long employeeDbId) {
        try {
            Path path = Paths.get(faceStorageDir, "enrolled", "employee_" + employeeDbId + ".jpg");
            if (!Files.exists(path)) {
                return null;
            }
            return Files.readAllBytes(path);
        } catch (Exception e) {
            log.warn("Could not read enrolled photo for employee #{}: {}", employeeDbId, e.getMessage());
            return null;
        }
    }
}
