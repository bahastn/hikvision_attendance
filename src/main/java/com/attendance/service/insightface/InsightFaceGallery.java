package com.attendance.service.insightface;

import com.attendance.entity.Employee;
import com.attendance.entity.EmployeeFaceEmbedding;
import com.attendance.repository.EmployeeFaceEmbeddingRepository;
import com.attendance.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory gallery of enrolled employees' face embeddings for fast matching, kept in sync with the
 * {@code employee_face_embeddings} table. Only active employees with an embedding are held.
 *
 * <p>Thread-safe: reads take a snapshot of the concurrent map; enroll/remove mutate it. The vectors
 * are stored L2-normalized so matching ({@link FaceMatcher}) is a plain dot product.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InsightFaceGallery {

    private final EmployeeFaceEmbeddingRepository embeddingRepository;
    private final EmployeeRepository employeeRepository;

    @Value("${insightface.enabled:false}")
    private boolean enabled;

    /** employee database id -> normalized embedding. */
    private final Map<Long, float[]> vectors = new ConcurrentHashMap<>();
    /** employee database id -> business employeeId (what attendance recording needs). */
    private final Map<Long, String> businessIds = new ConcurrentHashMap<>();

    @PostConstruct
    @Transactional(readOnly = true)
    public void loadAtStartup() {
        if (!enabled) {
            return;
        }
        try {
            reloadAll();
        } catch (Exception e) {
            log.warn("Could not load InsightFace gallery at startup: {}", e.getMessage());
        }
    }

    /** Rebuild the whole gallery from the database. */
    @Transactional(readOnly = true)
    public void reloadAll() {
        Map<Long, float[]> newVectors = new HashMap<>();
        Map<Long, String> newBusinessIds = new HashMap<>();
        for (EmployeeFaceEmbedding row : embeddingRepository.findAll()) {
            Long employeeDbId = row.getEmployeeId();
            if (employeeDbId == null || row.getEmbedding() == null) {
                continue;
            }
            employeeRepository.findById(employeeDbId)
                    .filter(e -> Boolean.TRUE.equals(e.getIsActive()))
                    .ifPresent(e -> {
                        newVectors.put(employeeDbId, EmbeddingCodec.toFloats(row.getEmbedding()));
                        newBusinessIds.put(employeeDbId, e.getEmployeeId());
                    });
        }
        vectors.clear();
        vectors.putAll(newVectors);
        businessIds.clear();
        businessIds.putAll(newBusinessIds);
        log.info("InsightFace gallery loaded: {} employee embedding(s)", vectors.size());
    }

    /** Add or replace one employee's embedding in the live gallery. */
    public void put(Employee employee, float[] normalizedVector) {
        if (employee == null || employee.getId() == null || normalizedVector == null) {
            return;
        }
        vectors.put(employee.getId(), normalizedVector);
        businessIds.put(employee.getId(), employee.getEmployeeId());
    }

    /** Remove one employee from the live gallery. */
    public void evict(Long employeeDbId) {
        if (employeeDbId == null) {
            return;
        }
        vectors.remove(employeeDbId);
        businessIds.remove(employeeDbId);
    }

    public int size() {
        return vectors.size();
    }

    /** A point-in-time copy of the gallery for matching. */
    public Map<Long, float[]> snapshot() {
        return new HashMap<>(vectors);
    }

    /** Business employeeId for a database id, if present in the gallery. */
    public Optional<String> businessIdOf(Long employeeDbId) {
        return Optional.ofNullable(businessIds.get(employeeDbId));
    }
}
