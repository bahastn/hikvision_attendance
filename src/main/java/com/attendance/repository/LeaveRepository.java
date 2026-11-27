package com.attendance.repository;

import com.attendance.entity.Leave;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface LeaveRepository extends JpaRepository<Leave, Long> {

    List<Leave> findByEmployeeId(Long employeeId);

    List<Leave> findByStatus(Leave.LeaveStatus status);

    List<Leave> findByLeaveTypeId(Long leaveTypeId);

    long countByLeaveTypeId(Long leaveTypeId);

    @Query("SELECT l FROM Leave l WHERE l.status = 'APPROVED' AND l.startDate <= :date AND l.endDate >= :date")
    List<Leave> findActiveLeavesOnDate(LocalDate date);

    @Query("SELECT l FROM Leave l WHERE l.status = 'APPROVED' AND l.endDate >= :date ORDER BY l.startDate ASC")
    List<Leave> findUpcomingAndActiveLeaves(LocalDate date);

    @Query("SELECT l FROM Leave l WHERE l.status = 'PENDING' ORDER BY l.createdAt ASC")
    List<Leave> findAllPending();

    @Query("SELECT l FROM Leave l ORDER BY l.createdAt DESC")
    List<Leave> findAllOrdered();
}
