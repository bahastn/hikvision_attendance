package com.attendance.repository;

import com.attendance.entity.EmployeeGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeGroupRepository extends JpaRepository<EmployeeGroup, Long> {

    Optional<EmployeeGroup> findByName(String name);

    List<EmployeeGroup> findByIsActiveTrue();

    List<EmployeeGroup> findByShiftId(Long shiftId);

    long countByIsActiveTrue();
}
