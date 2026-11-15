package com.attendance.service;

import com.attendance.dto.ShiftRuleDTO;
import com.attendance.entity.ShiftRule;
import com.attendance.repository.EmployeeGroupRepository;
import com.attendance.repository.ShiftRuleRepository;
import com.attendance.repository.ShiftScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShiftRuleService {

    private final ShiftRuleRepository ruleRepository;
    private final EmployeeGroupRepository groupRepository;
    private final ShiftScheduleRepository scheduleRepository;

    @Transactional(readOnly = true)
    public List<ShiftRuleDTO> getAllRules() {
        return ruleRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ShiftRuleDTO> getActiveRules() {
        return ruleRepository.findByIsActiveTrue().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ShiftRuleDTO getRuleById(Long id) {
        ShiftRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Shift rule not found"));
        return toDTO(rule);
    }

    @Transactional
    public ShiftRuleDTO createRule(ShiftRuleDTO dto) {
        ShiftRule rule = ShiftRule.builder()
                .name(dto.getName())
                .description(dto.getDescription())
                .lateThresholdMinutes(dto.getLateThresholdMinutes() != null ? dto.getLateThresholdMinutes() : 15)
                .earlyLeaveThresholdMinutes(dto.getEarlyLeaveThresholdMinutes() != null ? dto.getEarlyLeaveThresholdMinutes() : 15)
                .absentAfterMinutes(dto.getAbsentAfterMinutes() != null ? dto.getAbsentAfterMinutes() : 240)
                .requireCheckIn(dto.getRequireCheckIn() != null ? dto.getRequireCheckIn() : true)
                .requireCheckOut(dto.getRequireCheckOut() != null ? dto.getRequireCheckOut() : true)
                .gracePeriodMinutes(dto.getGracePeriodMinutes() != null ? dto.getGracePeriodMinutes() : 0)
                .maxWorkHours(dto.getMaxWorkHours())
                .isActive(true)
                .build();
        return toDTO(ruleRepository.save(rule));
    }

    @Transactional
    public ShiftRuleDTO updateRule(Long id, ShiftRuleDTO dto) {
        ShiftRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Shift rule not found"));

        rule.setName(dto.getName());
        rule.setDescription(dto.getDescription());
        rule.setLateThresholdMinutes(dto.getLateThresholdMinutes() != null ? dto.getLateThresholdMinutes() : 15);
        rule.setEarlyLeaveThresholdMinutes(dto.getEarlyLeaveThresholdMinutes() != null ? dto.getEarlyLeaveThresholdMinutes() : 15);
        rule.setAbsentAfterMinutes(dto.getAbsentAfterMinutes() != null ? dto.getAbsentAfterMinutes() : 240);
        rule.setRequireCheckIn(dto.getRequireCheckIn() != null ? dto.getRequireCheckIn() : true);
        rule.setRequireCheckOut(dto.getRequireCheckOut() != null ? dto.getRequireCheckOut() : true);
        rule.setGracePeriodMinutes(dto.getGracePeriodMinutes() != null ? dto.getGracePeriodMinutes() : 0);
        rule.setMaxWorkHours(dto.getMaxWorkHours());
        rule.setIsActive(dto.getIsActive());

        return toDTO(ruleRepository.save(rule));
    }

    @Transactional
    public void deleteRule(Long id) {
        ShiftRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Shift rule not found"));
        rule.setIsActive(false);
        ruleRepository.save(rule);
    }

    /**
     * Permanently delete a shift rule: removes rule from all groups and schedules, then deletes the rule.
     * This cannot be undone.
     */
    @Transactional
    public void permanentlyDeleteRule(Long id) {
        ShiftRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Shift rule not found"));
        
        // Remove rule from all groups
        List<com.attendance.entity.EmployeeGroup> groups = groupRepository.findAll().stream()
                .filter(g -> g.getShiftRule() != null && g.getShiftRule().getId().equals(id))
                .collect(Collectors.toList());
        groups.forEach(g -> g.setShiftRule(null));
        groupRepository.saveAll(groups);
        
        // Remove rule from all schedules
        List<com.attendance.entity.ShiftSchedule> schedules = scheduleRepository.findByRuleId(id);
        schedules.forEach(s -> s.setRule(null));
        scheduleRepository.saveAll(schedules);
        
        // Delete the rule permanently
        ruleRepository.delete(rule);
    }

    @Transactional(readOnly = true)
    public long getTotalCount() {
        return ruleRepository.countByIsActiveTrue();
    }

    private ShiftRuleDTO toDTO(ShiftRule rule) {
        int groupCount = (int) groupRepository.findAll().stream()
                .filter(g -> g.getShiftRule() != null && g.getShiftRule().getId().equals(rule.getId()))
                .count();
        int scheduleCount = scheduleRepository.findByRuleId(rule.getId()).size();

        return ShiftRuleDTO.builder()
                .id(rule.getId())
                .name(rule.getName())
                .description(rule.getDescription())
                .lateThresholdMinutes(rule.getLateThresholdMinutes())
                .earlyLeaveThresholdMinutes(rule.getEarlyLeaveThresholdMinutes())
                .absentAfterMinutes(rule.getAbsentAfterMinutes())
                .requireCheckIn(rule.getRequireCheckIn())
                .requireCheckOut(rule.getRequireCheckOut())
                .gracePeriodMinutes(rule.getGracePeriodMinutes())
                .maxWorkHours(rule.getMaxWorkHours())
                .isActive(rule.getIsActive())
                .groupCount(groupCount)
                .scheduleCount(scheduleCount)
                .build();
    }
}
