package com.attendance.repository;

import com.attendance.entity.EmployeeFaceSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface EmployeeFaceSampleRepository extends JpaRepository<EmployeeFaceSample, Long> {

    List<EmployeeFaceSample> findByEmployeeIdOrderByCreatedAtAsc(Long employeeId);

    List<EmployeeFaceSample> findByEmployeeIdAndSource(Long employeeId, EmployeeFaceSample.Source source);

    List<EmployeeFaceSample> findByEmployeeIdAndSourceInOrderByCreatedAtAsc(
            Long employeeId, Collection<EmployeeFaceSample.Source> sources);

    long countByEmployeeId(Long employeeId);

    java.util.Optional<EmployeeFaceSample> findTopByEmployeeIdAndSourceOrderByCreatedAtDesc(
            Long employeeId, EmployeeFaceSample.Source source);

    boolean existsByEmployeeId(Long employeeId);

    boolean existsBySourceCaptureId(Long sourceCaptureId);

    void deleteByEmployeeId(Long employeeId);
}
