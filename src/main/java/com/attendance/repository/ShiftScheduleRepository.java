package com.attendance.repository;

import com.attendance.entity.ShiftSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ShiftScheduleRepository extends JpaRepository<ShiftSchedule, Long> {

    List<ShiftSchedule> findByGroupId(Long groupId);

    List<ShiftSchedule> findByShiftId(Long shiftId);

    List<ShiftSchedule> findByRuleId(Long ruleId);

    List<ShiftSchedule> findByIsActiveTrue();

    @Query("SELECT ss FROM ShiftSchedule ss WHERE ss.group.id = :groupId AND ss.isActive = true AND ss.startDate <= :date AND ss.endDate >= :date")
    List<ShiftSchedule> findActiveByGroupAndDate(@Param("groupId") Long groupId, @Param("date") LocalDate date);

    @Query("SELECT ss FROM ShiftSchedule ss WHERE ss.isActive = true AND ss.startDate <= :date AND ss.endDate >= :date")
    List<ShiftSchedule> findAllActiveOnDate(@Param("date") LocalDate date);

    @Query("SELECT ss FROM ShiftSchedule ss WHERE ss.isActive = true AND ss.endDate >= :date ORDER BY ss.startDate ASC")
    List<ShiftSchedule> findUpcomingAndActive(@Param("date") LocalDate date);
}
