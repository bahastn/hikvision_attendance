package com.attendance.service;

import com.attendance.dto.ShiftScheduleDTO;
import com.attendance.entity.EmployeeGroup;
import com.attendance.entity.Shift;
import com.attendance.entity.ShiftRule;
import com.attendance.entity.ShiftSchedule;
import com.attendance.repository.EmployeeGroupRepository;
import com.attendance.repository.EmployeeRepository;
import com.attendance.repository.ShiftRepository;
import com.attendance.repository.ShiftRuleRepository;
import com.attendance.repository.ShiftScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShiftScheduleService {

    private final ShiftScheduleRepository scheduleRepository;
    private final EmployeeGroupRepository groupRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftRuleRepository ruleRepository;
    private final EmployeeRepository employeeRepository;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    @Transactional(readOnly = true)
    public List<ShiftScheduleDTO> getAllSchedules() {
        return scheduleRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ShiftScheduleDTO> getActiveSchedules() {
        return scheduleRepository.findByIsActiveTrue().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ShiftScheduleDTO getScheduleById(Long id) {
        ShiftSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Shift schedule not found"));
        return toDTO(schedule);
    }

    @Transactional(readOnly = true)
    public List<ShiftScheduleDTO> getSchedulesByGroup(Long groupId) {
        return scheduleRepository.findByGroupId(groupId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ShiftScheduleDTO> getSchedulesByDate(LocalDate date) {
        return scheduleRepository.findAllActiveOnDate(date).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ShiftScheduleDTO> getUpcomingAndActiveSchedules() {
        return scheduleRepository.findUpcomingAndActive(LocalDate.now()).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public ShiftScheduleDTO createSchedule(ShiftScheduleDTO dto) {
        EmployeeGroup group = groupRepository.findById(dto.getGroupId())
                .orElseThrow(() -> new RuntimeException("Group not found"));
        Shift shift = shiftRepository.findById(dto.getShiftId())
                .orElseThrow(() -> new RuntimeException("Shift not found"));
        ShiftRule rule = null;
        if (dto.getRuleId() != null) {
            rule = ruleRepository.findById(dto.getRuleId())
                    .orElseThrow(() -> new RuntimeException("Shift rule not found"));
        }

        ShiftSchedule schedule = ShiftSchedule.builder()
                .group(group)
                .shift(shift)
                .rule(rule)
                .startDate(dto.getStartDate())
                .endDate(dto.getEndDate())
                .notes(dto.getNotes())
                .isActive(true)
                .build();

        return toDTO(scheduleRepository.save(schedule));
    }

    @Transactional
    public ShiftScheduleDTO updateSchedule(Long id, ShiftScheduleDTO dto) {
        ShiftSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Shift schedule not found"));

        EmployeeGroup group = groupRepository.findById(dto.getGroupId())
                .orElseThrow(() -> new RuntimeException("Group not found"));
        Shift shift = shiftRepository.findById(dto.getShiftId())
                .orElseThrow(() -> new RuntimeException("Shift not found"));
        ShiftRule rule = null;
        if (dto.getRuleId() != null) {
            rule = ruleRepository.findById(dto.getRuleId())
                    .orElseThrow(() -> new RuntimeException("Shift rule not found"));
        }

        schedule.setGroup(group);
        schedule.setShift(shift);
        schedule.setRule(rule);
        schedule.setStartDate(dto.getStartDate());
        schedule.setEndDate(dto.getEndDate());
        schedule.setNotes(dto.getNotes());
        schedule.setIsActive(dto.getIsActive());

        return toDTO(scheduleRepository.save(schedule));
    }

    @Transactional
    public void deleteSchedule(Long id) {
        ShiftSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Shift schedule not found"));
        schedule.setIsActive(false);
        scheduleRepository.save(schedule);
    }

    /**
     * Get the effective schedule for an employee on a given date.
     * Looks up the employee's group, then finds an active schedule for that group on the date.
     * Falls back to the group's direct shift/rule if no schedule exists.
     */
    @Transactional(readOnly = true)
    public ShiftSchedule getEffectiveScheduleForEmployee(Long employeeId, LocalDate date) {
        return employeeRepository.findById(employeeId)
                .map(emp -> emp.getGroup())
                .filter(g -> g != null && Boolean.TRUE.equals(g.getIsActive()))
                .map(g -> {
                    // First, try to find an active schedule for this group on the given date
                    List<ShiftSchedule> schedules = scheduleRepository
                            .findActiveByGroupAndDate(g.getId(), date);
                    if (!schedules.isEmpty()) {
                        return schedules.get(0);
                    }
                    return null;
                })
                .orElse(null);
    }

    private ShiftScheduleDTO toDTO(ShiftSchedule schedule) {
        int employeeCount = (int) employeeRepository.findAll().stream()
                .filter(e -> e.getGroup() != null && e.getGroup().getId().equals(schedule.getGroup().getId()))
                .count();

        Shift shift = schedule.getShift();
        return ShiftScheduleDTO.builder()
                .id(schedule.getId())
                .groupId(schedule.getGroup().getId())
                .groupName(schedule.getGroup().getName())
                .shiftId(shift.getId())
                .shiftName(shift.getName())
                .shiftStartTime(shift.getStartTime() != null ? shift.getStartTime().format(TIME_FORMATTER) : null)
                .shiftEndTime(shift.getEndTime() != null ? shift.getEndTime().format(TIME_FORMATTER) : null)
                .ruleId(schedule.getRule() != null ? schedule.getRule().getId() : null)
                .ruleName(schedule.getRule() != null ? schedule.getRule().getName() : null)
                .startDate(schedule.getStartDate())
                .endDate(schedule.getEndDate())
                .notes(schedule.getNotes())
                .isActive(schedule.getIsActive())
                .currentlyActive(schedule.isCurrentlyActive())
                .employeeCount(employeeCount)
                .build();
    }
}
