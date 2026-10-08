package com.attendance.service;

import com.attendance.dto.LeaveDTO;
import com.attendance.entity.Employee;
import com.attendance.entity.Leave;
import com.attendance.repository.EmployeeRepository;
import com.attendance.repository.LeaveRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeaveService {

    private final LeaveRepository leaveRepository;
    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public List<LeaveDTO> getAllLeaves() {
        return leaveRepository.findAllOrdered().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveDTO> getLeavesByStatus(String status) {
        Leave.LeaveStatus leaveStatus = Leave.LeaveStatus.valueOf(status.toUpperCase());
        return leaveRepository.findByStatus(leaveStatus).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveDTO> getLeavesByType(String type) {
        Leave.LeaveType leaveType = Leave.LeaveType.valueOf(type.toUpperCase());
        return leaveRepository.findByLeaveType(leaveType).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveDTO> getActiveLeavesToday() {
        return leaveRepository.findActiveLeavesOnDate(LocalDate.now()).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveDTO> getUpcomingAndActiveLeaves() {
        return leaveRepository.findUpcomingAndActiveLeaves(LocalDate.now()).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveDTO> getPendingLeaves() {
        return leaveRepository.findAllPending().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveDTO> getLeavesByEmployee(Long employeeId) {
        return leaveRepository.findByEmployeeId(employeeId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public LeaveDTO createLeave(LeaveDTO dto) {
        Employee employee = employeeRepository.findById(dto.getEmployeeDbId())
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        Leave leave = Leave.builder()
                .employee(employee)
                .leaveType(Leave.LeaveType.valueOf(dto.getLeaveType().toUpperCase()))
                .startDate(dto.getStartDate())
                .endDate(dto.getEndDate())
                .reason(dto.getReason())
                .status(Leave.LeaveStatus.PENDING)
                .build();

        return toDTO(leaveRepository.save(leave));
    }

    @Transactional
    public LeaveDTO approveLeave(Long id, String approvedBy) {
        Leave leave = leaveRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Leave not found"));
        leave.setStatus(Leave.LeaveStatus.APPROVED);
        leave.setApprovedBy(approvedBy);
        leave.setApprovedAt(java.time.LocalDateTime.now());
        return toDTO(leaveRepository.save(leave));
    }

    @Transactional
    public LeaveDTO rejectLeave(Long id, String approvedBy) {
        Leave leave = leaveRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Leave not found"));
        leave.setStatus(Leave.LeaveStatus.REJECTED);
        leave.setApprovedBy(approvedBy);
        leave.setApprovedAt(java.time.LocalDateTime.now());
        return toDTO(leaveRepository.save(leave));
    }

    @Transactional
    public LeaveDTO cancelLeave(Long id) {
        Leave leave = leaveRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Leave not found"));
        leave.setStatus(Leave.LeaveStatus.CANCELLED);
        return toDTO(leaveRepository.save(leave));
    }

    @Transactional
    public void deleteLeave(Long id) {
        leaveRepository.deleteById(id);
    }

    private LeaveDTO toDTO(Leave leave) {
        int totalDays = (int) ChronoUnit.DAYS.between(leave.getStartDate(), leave.getEndDate()) + 1;
        return LeaveDTO.builder()
                .id(leave.getId())
                .employeeDbId(leave.getEmployee().getId())
                .employeeId(leave.getEmployee().getEmployeeId())
                .employeeName(leave.getEmployee().getFullName())
                .department(leave.getEmployee().getDepartment())
                .leaveType(leave.getLeaveType().name())
                .startDate(leave.getStartDate())
                .endDate(leave.getEndDate())
                .totalDays(totalDays)
                .reason(leave.getReason())
                .status(leave.getStatus().name())
                .approvedBy(leave.getApprovedBy())
                .approvedAt(leave.getApprovedAt())
                .createdAt(leave.getCreatedAt())
                .build();
    }
}
