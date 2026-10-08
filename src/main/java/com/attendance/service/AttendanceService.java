package com.attendance.service;

import com.attendance.dto.AttendanceDTO;
import com.attendance.dto.SyncMessage;
import com.attendance.entity.AttendanceRecord;
import com.attendance.entity.Employee;
import com.attendance.entity.Leave;
import com.attendance.entity.Shift;
import com.attendance.entity.ShiftRule;
import com.attendance.entity.ShiftSchedule;
import com.attendance.repository.AttendanceRecordRepository;
import com.attendance.repository.EmployeeRepository;
import com.attendance.repository.LeaveRepository;
import com.attendance.websocket.AttendanceWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceService {

    private final AttendanceRecordRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveRepository leaveRepository;
    private final AttendanceWebSocketService webSocketService;
    private final ShiftService shiftService;
    private final ShiftScheduleService shiftScheduleService;

    @Value("${attendance.deduplication.seconds:60}")
    private int deduplicationSeconds;

    @Value("${attendance.work-start-time:08:00}")
    private String workStartTime;

    @Value("${attendance.work-end-time:17:00}")
    private String workEndTime;

    @Transactional(readOnly = true)
    public List<AttendanceDTO> getTodayAttendance() {
        return attendanceRepository.findTodayAll(LocalDate.now()).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AttendanceDTO> getAttendanceByDate(LocalDate date) {
        return attendanceRepository.findByAttendanceDate(date).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<AttendanceDTO> getEmployeeAttendance(Long employeeId, LocalDate start, LocalDate end) {
        return attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(employeeId, start, end)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Transactional
    public AttendanceDTO processDetection(String employeeId, String deviceId,
                                          Double similarity, String direction, String faceImageUrl) {
        Employee employee = employeeRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found: " + employeeId));

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        // Deduplication check
        LocalDateTime since = now.minusSeconds(deduplicationSeconds);
        List<AttendanceRecord> recent = attendanceRepository.findRecentByEmployee(employee.getId(), since);
        if (!recent.isEmpty()) {
            log.info("Deduplication: skipped duplicate detection for {} within {} seconds",
                    employeeId, deduplicationSeconds);
            return toDTO(recent.get(0));
        }

        Optional<AttendanceRecord> existing = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employee.getId(), today);

        AttendanceRecord record;
        if (existing.isPresent() && "OUT".equalsIgnoreCase(direction)) {
            record = existing.get();
            record.setCheckOut(now);
            record.setWorkHours(calculateWorkHours(record.getCheckIn(), now));
            record.setStatus(determineStatus(employee, record.getCheckIn(), now));
            record.setDeviceId(deviceId);
            record.setSimilarityScore(similarity);
        } else {
            record = AttendanceRecord.builder()
                    .employee(employee)
                    .attendanceDate(today)
                    .checkIn(now)
                    .status(determineCheckInStatus(employee, now))
                    .deviceId(deviceId)
                    .similarityScore(similarity)
                    .direction(direction)
                    .faceImageUrl(faceImageUrl)
                    .build();
        }

        AttendanceRecord saved = attendanceRepository.save(record);

        // Broadcast via WebSocket
        webSocketService.notifyNewDetection(saved);
        broadcastStats();

        return toDTO(saved);
    }

    @Transactional
    public AttendanceDTO manualCheckIn(Long employeeId) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        AttendanceRecord record = AttendanceRecord.builder()
                .employee(employee)
                .attendanceDate(today)
                .checkIn(now)
                .status(determineCheckInStatus(employee, now))
                .deviceId("MANUAL")
                .notes("Manual check-in")
                .build();

        AttendanceRecord saved = attendanceRepository.save(record);
        webSocketService.notifyNewDetection(saved);
        broadcastStats();

        return toDTO(saved);
    }

    @Transactional
    public AttendanceDTO manualCheckOut(Long recordId) {
        AttendanceRecord record = attendanceRepository.findById(recordId)
                .orElseThrow(() -> new RuntimeException("Record not found"));

        LocalDateTime now = LocalDateTime.now();
        record.setCheckOut(now);
        record.setWorkHours(calculateWorkHours(record.getCheckIn(), now));
        record.setStatus(determineStatus(record.getEmployee(), record.getCheckIn(), now));
        record.setNotes("Manual check-out");

        AttendanceRecord saved = attendanceRepository.save(record);
        broadcastStats();

        return toDTO(saved);
    }

    /**
     * True if the employee already has a check-in record today with no check-out.
     * Used by the camera poller to decide IN vs OUT direction.
     */
    @Transactional(readOnly = true)
    public boolean hasOpenCheckInToday(Long employeeId) {
        return attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employeeId, LocalDate.now())
                .filter(r -> r.getCheckOut() == null)
                .isPresent();
    }

    /**
     * Check if an employee is on approved leave for a given date.
     */
    @Transactional(readOnly = true)
    public boolean isOnLeave(Long employeeId, LocalDate date) {
        List<Leave> leaves = leaveRepository.findActiveLeavesOnDate(date);
        return leaves.stream()
                .anyMatch(l -> l.getEmployee().getId().equals(employeeId));
    }

    /**
     * Get all employees who should be at work today (active, working day for their shift, not on leave).
     * Employees on any type of approved leave are excluded from the expected list.
     */
    @Transactional(readOnly = true)
    public List<Employee> getExpectedEmployeesToday() {
        LocalDate today = LocalDate.now();
        DayOfWeek dayOfWeek = today.getDayOfWeek();

        List<Leave> activeLeaves = leaveRepository.findActiveLeavesOnDate(today);

        return employeeRepository.findByIsActiveTrue().stream()
                .filter(emp -> {
                    // Check if employee is on approved leave (any type) - they are NOT expected at work
                    boolean onLeave = activeLeaves.stream()
                            .anyMatch(l -> l.getEmployee().getId().equals(emp.getId()));
                    if (onLeave) return false;

                    // Check if today is a working day for their shift
                    Shift shift = resolveShift(emp, today);
                    if (shift != null) {
                        return shift.isWorkingDay(dayOfWeek);
                    }
                    // Default: Monday-Friday
                    return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
                })
                .collect(Collectors.toList());
    }

    /**
     * Get all employees who are absent today (expected but no check-in record).
     */
    @Transactional(readOnly = true)
    public List<Employee> getAbsentEmployeesToday() {
        LocalDate today = LocalDate.now();
        List<Employee> expected = getExpectedEmployeesToday();
        List<AttendanceRecord> todayRecords = attendanceRepository.findByAttendanceDate(today);

        return expected.stream()
                .filter(emp -> todayRecords.stream()
                        .noneMatch(r -> r.getEmployee().getId().equals(emp.getId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public void broadcastStats() {
        LocalDate today = LocalDate.now();

        // Count employees on leave today
        long onLeaveCount = leaveRepository.findActiveLeavesOnDate(today).size();

        // Get expected employees (excluding those on leave)
        List<Employee> expectedEmployees = getExpectedEmployeesToday();
        long expectedCount = expectedEmployees.size();

        // Count present/late from attendance records
        List<AttendanceRecord> todayRecords = attendanceRepository.findByAttendanceDate(today);
        long present = todayRecords.stream()
                .filter(r -> r.getStatus() == AttendanceRecord.AttendanceStatus.PRESENT)
                .count();
        long late = todayRecords.stream()
                .filter(r -> r.getStatus() == AttendanceRecord.AttendanceStatus.LATE)
                .count();

        // Absent = expected - those who checked in
        long checkedIn = todayRecords.stream()
                .map(r -> r.getEmployee().getId())
                .distinct()
                .count();
        long absent = Math.max(0, expectedCount - checkedIn);

        webSocketService.broadcastStatsUpdate((int) present, (int) absent, (int) late);
    }

    @Scheduled(fixedRateString = "${attendance.sync.interval:2000}")
    public void scheduledSync() {
        broadcastStats();
    }

    /**
     * Resolve the effective shift for an employee on a given date.
     * First checks for a scheduled shift, then falls back to the group's direct shift.
     */
    private Shift resolveShift(Employee employee, LocalDate date) {
        // First, check for a scheduled shift
        ShiftSchedule schedule = shiftScheduleService.getEffectiveScheduleForEmployee(employee.getId(), date);
        if (schedule != null && schedule.getShift() != null) {
            return schedule.getShift();
        }
        // Fall back to group's direct shift
        return shiftService.getShiftForEmployee(employee.getId());
    }

    /**
     * Resolve the effective shift rule for an employee on a given date.
     * First checks the scheduled rule, then falls back to the group's direct rule.
     */
    private ShiftRule resolveRule(Employee employee, LocalDate date) {
        // First, check for a scheduled rule
        ShiftSchedule schedule = shiftScheduleService.getEffectiveScheduleForEmployee(employee.getId(), date);
        if (schedule != null && schedule.getRule() != null) {
            return schedule.getRule();
        }
        // Fall back to group's direct rule
        if (employee.getGroup() != null && employee.getGroup().getShiftRule() != null
                && Boolean.TRUE.equals(employee.getGroup().getShiftRule().getIsActive())) {
            return employee.getGroup().getShiftRule();
        }
        return null;
    }

    /**
     * Determine check-in status using the employee's shift rules.
     * Handles night shifts (end time before start time).
     */
    private AttendanceRecord.AttendanceStatus determineCheckInStatus(Employee employee, LocalDateTime checkIn) {
        Shift shift = resolveShift(employee, checkIn.toLocalDate());
        ShiftRule rule = resolveRule(employee, checkIn.toLocalDate());
        LocalTime start;
        int lateThreshold;

        if (shift != null) {
            start = shift.getStartTime();
            lateThreshold = rule != null ? rule.getEffectiveLateThreshold()
                    : (shift.getLateThresholdMinutes() != null ? shift.getLateThresholdMinutes() : 15);
        } else {
            start = LocalTime.parse(workStartTime);
            lateThreshold = rule != null ? rule.getEffectiveLateThreshold() : 15;
        }

        LocalTime checkInTime = checkIn.toLocalTime();
        return checkInTime.isAfter(start.plusMinutes(lateThreshold)) ?
                AttendanceRecord.AttendanceStatus.LATE :
                AttendanceRecord.AttendanceStatus.PRESENT;
    }

    /**
     * Determine overall status using the employee's shift rules.
     * Handles night shifts (end time before start time).
     */
    private AttendanceRecord.AttendanceStatus determineStatus(Employee employee, LocalDateTime checkIn, LocalDateTime checkOut) {
        Shift shift = resolveShift(employee, checkIn.toLocalDate());
        ShiftRule rule = resolveRule(employee, checkIn.toLocalDate());
        LocalTime start;
        LocalTime end;
        int lateThreshold;
        int earlyLeaveThreshold;

        if (shift != null) {
            start = shift.getStartTime();
            end = shift.getEndTime();
            lateThreshold = rule != null ? rule.getEffectiveLateThreshold()
                    : (shift.getLateThresholdMinutes() != null ? shift.getLateThresholdMinutes() : 15);
            earlyLeaveThreshold = rule != null ? rule.getEffectiveEarlyLeaveThreshold()
                    : (shift.getEarlyLeaveThresholdMinutes() != null ? shift.getEarlyLeaveThresholdMinutes() : 15);
        } else {
            start = LocalTime.parse(workStartTime);
            end = LocalTime.parse(workEndTime);
            lateThreshold = rule != null ? rule.getEffectiveLateThreshold() : 15;
            earlyLeaveThreshold = rule != null ? rule.getEffectiveEarlyLeaveThreshold() : 15;
        }

        boolean isNightShift = end.isBefore(start);
        LocalTime checkInTime = checkIn.toLocalTime();
        LocalTime checkOutTime = checkOut.toLocalTime();

        boolean isLate;
        boolean isEarlyLeave;

        if (isNightShift) {
            // For night shifts: late if check-in is after start + threshold
            // Early leave if check-out is before end - threshold on the next day
            isLate = checkInTime.isAfter(start.plusMinutes(lateThreshold));
            // For night shift, check-out time is typically early morning (e.g., 6 AM)
            // If check-out is on the same calendar day as check-in and before midnight,
            // or if it's the next day
            if (checkOut.toLocalDate().equals(checkIn.toLocalDate())) {
                // Same day check-out for night shift means they left before midnight
                isEarlyLeave = true;
            } else {
                // Next day check-out
                isEarlyLeave = checkOutTime.isBefore(end.minusMinutes(earlyLeaveThreshold));
            }
        } else {
            isLate = checkInTime.isAfter(start.plusMinutes(lateThreshold));
            isEarlyLeave = checkOutTime.isBefore(end.minusMinutes(earlyLeaveThreshold));
        }

        if (isLate && isEarlyLeave) {
            return AttendanceRecord.AttendanceStatus.LATE;
        } else if (isLate) {
            return AttendanceRecord.AttendanceStatus.LATE;
        } else if (isEarlyLeave) {
            return AttendanceRecord.AttendanceStatus.EARLY_LEAVE;
        }
        return AttendanceRecord.AttendanceStatus.PRESENT;
    }

    private Double calculateWorkHours(LocalDateTime checkIn, LocalDateTime checkOut) {
        if (checkIn == null || checkOut == null) return 0.0;
        long minutes = ChronoUnit.MINUTES.between(checkIn, checkOut);
        return Math.round(minutes / 60.0 * 100.0) / 100.0;
    }

    private AttendanceDTO toDTO(AttendanceRecord record) {
        return AttendanceDTO.builder()
                .id(record.getId())
                .employeeId(record.getEmployee().getEmployeeId())
                .employeeName(record.getEmployee().getFullName())
                .department(record.getEmployee().getDepartment())
                .attendanceDate(record.getAttendanceDate())
                .checkIn(record.getCheckIn())
                .checkOut(record.getCheckOut())
                .workHours(record.getWorkHours())
                .status(record.getStatus().name())
                .deviceId(record.getDeviceId())
                .similarityScore(record.getSimilarityScore())
                .faceImageUrl(record.getFaceImageUrl())
                .direction(record.getDirection())
                .notes(record.getNotes())
                .build();
    }
}
