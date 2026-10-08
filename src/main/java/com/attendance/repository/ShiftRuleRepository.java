package com.attendance.repository;

import com.attendance.entity.ShiftRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShiftRuleRepository extends JpaRepository<ShiftRule, Long> {

    Optional<ShiftRule> findByName(String name);

    List<ShiftRule> findByIsActiveTrue();

    long countByIsActiveTrue();
}
