package com.attendance.repository;

import com.attendance.entity.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByEmployeeId(String employeeId);

    Optional<Employee> findByFaceTemplateId(String faceTemplateId);

    List<Employee> findByDepartment(String department);

    List<Employee> findByIsActiveTrue();

    @Query("SELECT e FROM Employee e WHERE e.faceTemplateId IS NOT NULL")
    List<Employee> findAllWithFaceTemplate();

    long countByIsActiveTrue();

    long countByDepartment(String department);
}
