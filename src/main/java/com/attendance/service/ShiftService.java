package com.attendance.service;

import com.attendance.dto.ShiftDTO;
import com.attendance.entity.Employee;
import com.attendance.entity.Shift;
import com.attendance.repository.EmployeeGroupRepository;
import com.attendance.repository.EmployeeRepository;
import com.attendance.repository.ShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShiftService {

    private final ShiftRepository shiftRepository;
    private final EmployeeGroupRepository groupRepository;
    private final EmployeeRepository employeeRepository;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    @Transactional(readOnly = true)
    public List<ShiftDTO> getAllShifts() {
        return shiftRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ShiftDTO> getActiveShifts() {
        return shiftRepository.findByIsActiveTrue().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ShiftDTO getShiftById(Long id) {
        Shift shift = shiftRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Shift not found"));
        return toDTO(shift);
    }

    @Transactional
    public ShiftDTO createShift(ShiftDTO dto) {
        Shift shift = Shift.builder()
                .name(dto.getName())
                .description(dto.getDescription())
                .startTime(LocalTime.parse(dto.getStartTime()))
                .endTime(LocalTime.parse(dto.getEndTime()))
                .lateThresholdMinutes(dto.getLateThresholdMinutes() != null ? dto.getLateThresholdMinutes() : 15)
                .earlyLeaveThresholdMinutes(dto.getEarlyLeaveThresholdMinutes() != null ? dto.getEarlyLeaveThresholdMinutes() : 15)
                .breakStart(dto.getBreakStart() != null && !dto.getBreakStart().isBlank() ? LocalTime.parse(dto.getBreakStart()) : null)
                .breakEnd(dto.getBreakEnd() != null && !dto.getBreakEnd().isBlank() ? LocalTime.parse(dto.getBreakEnd()) : null)
                .workingDays(dto.getWorkingDays() != null && !dto.getWorkingDays().isBlank() ? dto.getWorkingDays() : "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY")
                .isActive(true)
                .build();
        return toDTO(shiftRepository.save(shift));
    }

    @Transactional
    public ShiftDTO updateShift(Long id, ShiftDTO dto) {
        Shift shift = shiftRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Shift not found"));

        shift.setName(dto.getName());
        shift.setDescription(dto.getDescription());
        shift.setStartTime(LocalTime.parse(dto.getStartTime()));
        shift.setEndTime(LocalTime.parse(dto.getEndTime()));
        shift.setLateThresholdMinutes(dto.getLateThresholdMinutes() != null ? dto.getLateThresholdMinutes() : 15);
        shift.setEarlyLeaveThresholdMinutes(dto.getEarlyLeaveThresholdMinutes() != null ? dto.getEarlyLeaveThresholdMinutes() : 15);
        shift.setBreakStart(dto.getBreakStart() != null && !dto.getBreakStart().isBlank() ? LocalTime.parse(dto.getBreakStart()) : null);
        shift.setBreakEnd(dto.getBreakEnd() != null && !dto.getBreakEnd().isBlank() ? LocalTime.parse(dto.getBreakEnd()) : null);
        shift.setWorkingDays(dto.getWorkingDays() != null && !dto.getWorkingDays().isBlank() ? dto.getWorkingDays() : "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY");
        shift.setIsActive(dto.getIsActive());

        return toDTO(shiftRepository.save(shift));
    }

    @Transactional
    public void deleteShift(Long id) {
        Shift shift = shiftRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Shift not found"));
        shift.setIsActive(false);
        shiftRepository.save(shift);
    }

    @Transactional(readOnly = true)
    public long getTotalCount() {
        return shiftRepository.countByIsActiveTrue();
    }

    /**
     * Get the effective shift for an employee based on their group.
     * Returns null if the employee has no group or the group has no shift.
     */
    @Transactional(readOnly = true)
    public Shift getShiftForEmployee(Long employeeId) {
        return employeeRepository.findById(employeeId)
                .map(Employee::getGroup)
                .filter(g -> g != null && Boolean.TRUE.equals(g.getIsActive()))
                .map(g -> g.getShift())
                .filter(s -> s != null && Boolean.TRUE.equals(s.getIsActive()))
                .orElse(null);
    }

    private ShiftDTO toDTO(Shift shift) {
        int groupCount = groupRepository.findByShiftId(shift.getId()).size();

        return ShiftDTO.builder()
                .id(shift.getId())
                .name(shift.getName())
                .description(shift.getDescription())
                .startTime(shift.getStartTime() != null ? shift.getStartTime().format(TIME_FORMATTER) : null)
                .endTime(shift.getEndTime() != null ? shift.getEndTime().format(TIME_FORMATTER) : null)
                .lateThresholdMinutes(shift.getLateThresholdMinutes())
                .earlyLeaveThresholdMinutes(shift.getEarlyLeaveThresholdMinutes())
                .breakStart(shift.getBreakStart() != null ? shift.getBreakStart().format(TIME_FORMATTER) : null)
                .breakEnd(shift.getBreakEnd() != null ? shift.getBreakEnd().format(TIME_FORMATTER) : null)
                .workingDays(shift.getWorkingDays())
                .isActive(shift.getIsActive())
                .groupCount(groupCount)
                .build();
    }
}
