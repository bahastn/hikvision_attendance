package com.attendance.service.insightface;

import com.attendance.entity.Employee;
import com.attendance.entity.EmployeeFaceSample;
import com.attendance.repository.EmployeeFaceSampleRepository;
import com.attendance.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * In-memory gallery of enrolled employees' face samples for fast matching, kept in sync with the
 * {@code employee_face_samples} table. Only active employees with at least one sample are held.
 *
 * <p>Each employee maps to an immutable list of {@link FaceMatcher.Sample}s; changes replace the
 * whole list, so readers never see a half-updated employee. Vectors are L2-normalized.
 * Memory: 512 floats = 2 KB per sample, so 1,000 employees x 10 samples is about 20 MB.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InsightFaceGallery {

    private final EmployeeFaceSampleRepository sampleRepository;
    private final EmployeeRepository employeeRepository;

    @Value("${insightface.enabled:false}")
    private boolean enabled;

    /** Embeddings produced by other models are not comparable and are skipped. */
    @Value("${insightface.model:buffalo_l}")
    private String model;

    /** employee database id -> that employee's samples. */
    private final Map<Long, List<FaceMatcher.Sample>> samples = new ConcurrentHashMap<>();
    /** employee database id -> business employeeId. */
    private final Map<Long, String> businessIds = new ConcurrentHashMap<>();

    @PostConstruct
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

    /** Rebuild the whole gallery from the database (one query for samples, one for employees). */
    public synchronized void reloadAll() {
        List<EmployeeFaceSample> rows = sampleRepository.findAll();
        Set<Long> employeeIds = rows.stream().map(EmployeeFaceSample::getEmployeeId)
                .filter(id -> id != null).collect(Collectors.toSet());
        Map<Long, Employee> active = employeeRepository.findAllById(employeeIds).stream()
                .filter(e -> Boolean.TRUE.equals(e.getIsActive()))
                .collect(Collectors.toMap(Employee::getId, Function.identity()));

        Map<Long, List<FaceMatcher.Sample>> newSamples = new HashMap<>();
        int skippedModel = 0;
        for (EmployeeFaceSample row : rows) {
            Employee employee = active.get(row.getEmployeeId());
            if (employee == null || row.getEmbedding() == null) {
                continue;
            }
            if (!sameModel(row.getModel())) {
                skippedModel++;
                continue;
            }
            newSamples.computeIfAbsent(row.getEmployeeId(), k -> new ArrayList<>())
                    .add(new FaceMatcher.Sample(row.getId(), EmbeddingCodec.toFloats(row.getEmbedding())));
        }
        Map<Long, String> newBusinessIds = new HashMap<>();
        newSamples.keySet().forEach(id -> newBusinessIds.put(id, active.get(id).getEmployeeId()));

        samples.clear();
        newSamples.forEach((id, list) -> samples.put(id, Collections.unmodifiableList(list)));
        businessIds.clear();
        businessIds.putAll(newBusinessIds);
        log.info("InsightFace gallery loaded: {} employee(s), {} sample(s){}", samples.size(), sampleCount(),
                skippedModel > 0 ? " (" + skippedModel + " sample(s) from another model skipped — re-embed them)" : "");
    }

    /** Reload one employee's samples from the database (after samples were added or removed). */
    public void refreshEmployee(Long employeeDbId) {
        if (employeeDbId == null) {
            return;
        }
        Optional<Employee> employee = employeeRepository.findById(employeeDbId)
                .filter(e -> Boolean.TRUE.equals(e.getIsActive()));
        if (employee.isEmpty()) {
            evict(employeeDbId);
            return;
        }
        List<FaceMatcher.Sample> list = sampleRepository.findByEmployeeIdOrderByCreatedAtAsc(employeeDbId).stream()
                .filter(r -> r.getEmbedding() != null && sameModel(r.getModel()))
                .map(r -> new FaceMatcher.Sample(r.getId(), EmbeddingCodec.toFloats(r.getEmbedding())))
                .collect(Collectors.toList());
        if (list.isEmpty()) {
            evict(employeeDbId);
            return;
        }
        samples.put(employeeDbId, Collections.unmodifiableList(list));
        businessIds.put(employeeDbId, employee.get().getEmployeeId());
    }

    /** Remove one employee from the live gallery. */
    public void evict(Long employeeDbId) {
        if (employeeDbId == null) {
            return;
        }
        samples.remove(employeeDbId);
        businessIds.remove(employeeDbId);
    }

    /** Number of employees in the gallery. */
    public int size() {
        return samples.size();
    }

    /** Total number of samples in the gallery. */
    public int sampleCount() {
        return samples.values().stream().mapToInt(List::size).sum();
    }

    /** A point-in-time copy of the gallery for matching (the per-employee lists are immutable). */
    public Map<Long, List<FaceMatcher.Sample>> snapshot() {
        return new HashMap<>(samples);
    }

    /** One employee's samples (empty when not in the gallery). */
    public List<FaceMatcher.Sample> samplesOf(Long employeeDbId) {
        List<FaceMatcher.Sample> list = employeeDbId == null ? null : samples.get(employeeDbId);
        return list != null ? list : List.of();
    }

    /** Business employeeId for a database id, if present in the gallery. */
    public Optional<String> businessIdOf(Long employeeDbId) {
        return Optional.ofNullable(businessIds.get(employeeDbId));
    }

    private boolean sameModel(String rowModel) {
        return rowModel == null || rowModel.isBlank() || rowModel.equalsIgnoreCase(model);
    }
}
