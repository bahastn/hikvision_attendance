package com.attendance.repository;

import com.attendance.entity.EmployeeCameraTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeCameraTargetRepository extends JpaRepository<EmployeeCameraTarget, Long> {

    List<EmployeeCameraTarget> findByEmployeeId(Long employeeId);

    Optional<EmployeeCameraTarget> findByEmployeeIdAndCameraId(Long employeeId, Long cameraId);

    void deleteByEmployeeIdAndCameraId(Long employeeId, Long cameraId);

    void deleteByEmployeeId(Long employeeId);
}
