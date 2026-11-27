package com.attendance.repository;

import com.attendance.entity.EmployeeFaceEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmployeeFaceEmbeddingRepository extends JpaRepository<EmployeeFaceEmbedding, Long> {

    Optional<EmployeeFaceEmbedding> findByEmployeeId(Long employeeId);

    void deleteByEmployeeId(Long employeeId);
}
