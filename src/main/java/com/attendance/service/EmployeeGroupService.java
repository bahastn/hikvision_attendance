package com.attendance.service;

import com.attendance.dto.EmployeeGroupDTO;
import com.attendance.entity.Employee;
import com.attendance.entity.EmployeeGroup;
import com.attendance.entity.Shift;
import com.attendance.entity.ShiftRule;
import com.attendance.repository.EmployeeGroupRepository;
import com.attendance.repository.EmployeeRepository;
import com.attendance.repository.ShiftRepository;
import com.attendance.repository.ShiftRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeGroupService {

    private final EmployeeGroupRepository groupRepository;
    private final EmployeeRepository employeeRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftRuleRepository shiftRuleRepository;

    @Transactional(readOnly = true)
    public List<EmployeeGroupDTO> getAllGroups() {
        return groupRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmployeeGroupDTO> getActiveGroups() {
        return groupRepository.findByIsActiveTrue().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EmployeeGroupDTO getGroupById(Long id) {
        EmployeeGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Group not found"));
        return toDTO(group);
    }

    @Transactional
    public EmployeeGroupDTO createGroup(EmployeeGroupDTO dto) {
        Shift shift = null;
        if (dto.getShiftId() != null) {
            shift = shiftRepository.findById(dto.getShiftId())
                    .orElseThrow(() -> new RuntimeException("Shift not found"));
        }

        ShiftRule shiftRule = null;
        if (dto.getShiftRuleId() != null) {
            shiftRule = shiftRuleRepository.findById(dto.getShiftRuleId())
                    .orElseThrow(() -> new RuntimeException("Shift rule not found"));
        }

        EmployeeGroup group = EmployeeGroup.builder()
                .name(dto.getName())
                .description(dto.getDescription())
                .shift(shift)
                .shiftRule(shiftRule)
                .isActive(true)
                .build();

        EmployeeGroup saved = groupRepository.save(group);

        // Assign employees if provided
        if (dto.getEmployeeIds() != null && !dto.getEmployeeIds().isEmpty()) {
            assignEmployeesToGroup(saved.getId(), dto.getEmployeeIds());
        }

        return toDTO(groupRepository.findById(saved.getId()).orElse(saved));
    }

    @Transactional
    public EmployeeGroupDTO updateGroup(Long id, EmployeeGroupDTO dto) {
        EmployeeGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        group.setName(dto.getName());
        group.setDescription(dto.getDescription());
        group.setIsActive(dto.getIsActive());

        if (dto.getShiftId() != null) {
            Shift shift = shiftRepository.findById(dto.getShiftId())
                    .orElseThrow(() -> new RuntimeException("Shift not found"));
            group.setShift(shift);
        } else {
            group.setShift(null);
        }

        if (dto.getShiftRuleId() != null) {
            ShiftRule shiftRule = shiftRuleRepository.findById(dto.getShiftRuleId())
                    .orElseThrow(() -> new RuntimeException("Shift rule not found"));
            group.setShiftRule(shiftRule);
        } else {
            group.setShiftRule(null);
        }

        EmployeeGroup saved = groupRepository.save(group);

        // Re-assign employees if provided
        if (dto.getEmployeeIds() != null) {
            assignEmployeesToGroup(saved.getId(), dto.getEmployeeIds());
        }

        return toDTO(groupRepository.findById(saved.getId()).orElse(saved));
    }

    @Transactional
    public void deleteGroup(Long id) {
        EmployeeGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Group not found"));
        // Unassign all employees from this group
        List<Employee> employees = employeeRepository.findAll().stream()
                .filter(e -> e.getGroup() != null && e.getGroup().getId().equals(id))
                .collect(Collectors.toList());
        employees.forEach(e -> e.setGroup(null));
        employeeRepository.saveAll(employees);

        group.setIsActive(false);
        groupRepository.save(group);
    }

    @Transactional
    public void assignEmployeesToGroup(Long groupId, List<Long> employeeIds) {
        EmployeeGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new RuntimeException("Group not found"));

        // Remove current assignments for this group
        List<Employee> currentEmployees = employeeRepository.findAll().stream()
                .filter(e -> e.getGroup() != null && e.getGroup().getId().equals(groupId))
                .collect(Collectors.toList());
        currentEmployees.forEach(e -> e.setGroup(null));

        // Assign new employees
        List<Employee> newEmployees = employeeRepository.findAllById(employeeIds);
        newEmployees.forEach(e -> e.setGroup(group));

        employeeRepository.saveAll(currentEmployees);
        employeeRepository.saveAll(newEmployees);
    }

    @Transactional(readOnly = true)
    public long getTotalCount() {
        return groupRepository.countByIsActiveTrue();
    }

    private EmployeeGroupDTO toDTO(EmployeeGroup group) {
        int employeeCount = (int) employeeRepository.findAll().stream()
                .filter(e -> e.getGroup() != null && e.getGroup().getId().equals(group.getId()))
                .count();

        List<Long> employeeIds = employeeRepository.findAll().stream()
                .filter(e -> e.getGroup() != null && e.getGroup().getId().equals(group.getId()))
                .map(Employee::getId)
                .collect(Collectors.toList());

        return EmployeeGroupDTO.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .shiftId(group.getShift() != null ? group.getShift().getId() : null)
                .shiftName(group.getShift() != null ? group.getShift().getName() : null)
                .shiftRuleId(group.getShiftRule() != null ? group.getShiftRule().getId() : null)
                .shiftRuleName(group.getShiftRule() != null ? group.getShiftRule().getName() : null)
                .isActive(group.getIsActive())
                .employeeCount(employeeCount)
                .employeeIds(employeeIds)
                .build();
    }
}
