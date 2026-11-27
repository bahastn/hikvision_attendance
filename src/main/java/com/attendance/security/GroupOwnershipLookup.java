package com.attendance.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Finds the employee group that a record belongs to, for group-level access checks.
 * <p>
 * A missing record and an employee without a group both resolve to {@code null},
 * which only an "all groups" scope may access. That way a group-limited user cannot
 * tell "does not exist" apart from "not yours".
 */
@Component
@Transactional(readOnly = true)
public class GroupOwnershipLookup {

    @PersistenceContext
    private EntityManager em;

    public Long groupOfEmployee(Long employeeDbId) {
        if (employeeDbId == null) return null;
        return first(em.createQuery(
                        "select g.id from Employee e left join e.group g where e.id = :id", Long.class)
                .setParameter("id", employeeDbId)
                .getResultList());
    }

    public Long groupOfEmployeeCode(String employeeCode) {
        if (employeeCode == null) return null;
        return first(em.createQuery(
                        "select g.id from Employee e left join e.group g where e.employeeId = :code", Long.class)
                .setParameter("code", employeeCode)
                .getResultList());
    }

    public Long groupOfLeave(Long leaveId) {
        if (leaveId == null) return null;
        return first(em.createQuery(
                        "select g.id from Leave l join l.employee e left join e.group g where l.id = :id", Long.class)
                .setParameter("id", leaveId)
                .getResultList());
    }

    public Long groupOfAttendanceRecord(Long recordId) {
        if (recordId == null) return null;
        return first(em.createQuery(
                        "select g.id from AttendanceRecord r join r.employee e left join e.group g where r.id = :id", Long.class)
                .setParameter("id", recordId)
                .getResultList());
    }

    public Long groupOfSchedule(Long scheduleId) {
        if (scheduleId == null) return null;
        return first(em.createQuery(
                        "select s.group.id from ShiftSchedule s where s.id = :id", Long.class)
                .setParameter("id", scheduleId)
                .getResultList());
    }

    /** Current group of each employee id (null entries for ungrouped or missing employees). */
    public List<Long> groupsOfEmployees(List<Long> employeeDbIds) {
        if (employeeDbIds == null || employeeDbIds.isEmpty()) return List.of();
        return employeeDbIds.stream().map(this::groupOfEmployee).toList();
    }

    private static Long first(List<Long> rows) {
        return Optional.ofNullable(rows).filter(r -> !r.isEmpty()).map(r -> r.get(0)).orElse(null);
    }
}
