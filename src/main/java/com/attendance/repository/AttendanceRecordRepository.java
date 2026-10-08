package com.attendance.repository;

import com.attendance.entity.AttendanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

    List<AttendanceRecord> findByAttendanceDate(LocalDate date);

    List<AttendanceRecord> findByEmployeeIdAndAttendanceDateBetween(
            Long employeeId, LocalDate startDate, LocalDate endDate);

    Optional<AttendanceRecord> findByEmployeeIdAndAttendanceDate(Long employeeId, LocalDate date);

    @Query("SELECT ar FROM AttendanceRecord ar WHERE ar.attendanceDate = :date AND ar.checkOut IS NULL")
    List<AttendanceRecord> findCheckedInNotOut(@Param("date") LocalDate date);

    @Query("SELECT COUNT(ar) FROM AttendanceRecord ar WHERE ar.attendanceDate = :date AND ar.status = :status")
    long countByDateAndStatus(@Param("date") LocalDate date, @Param("status") AttendanceRecord.AttendanceStatus status);

    @Query("SELECT ar FROM AttendanceRecord ar WHERE ar.employee.id = :empId AND ar.checkIn >= :since ORDER BY ar.checkIn DESC")
    List<AttendanceRecord> findRecentByEmployee(@Param("empId") Long empId, @Param("since") LocalDateTime since);

    @Query("SELECT ar FROM AttendanceRecord ar WHERE ar.attendanceDate = :date ORDER BY ar.checkIn DESC")
    List<AttendanceRecord> findTodayAll(@Param("date") LocalDate date);

    @Query("SELECT COUNT(DISTINCT ar.employee.id) FROM AttendanceRecord ar WHERE ar.attendanceDate = :date")
    long countDistinctEmployeesByDate(@Param("date") LocalDate date);
}
