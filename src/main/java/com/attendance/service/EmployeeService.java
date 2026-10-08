package com.attendance.service;

import com.attendance.dto.EmployeeDTO;
import com.attendance.entity.Employee;
import com.attendance.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public List<EmployeeDTO> getAllEmployees() {
        return employeeRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmployeeDTO> getActiveEmployees() {
        return employeeRepository.findByIsActiveTrue().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EmployeeDTO getEmployeeById(Long id) {
        Employee emp = employeeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        return toDTO(emp);
    }

    @Transactional
    public EmployeeDTO createEmployee(EmployeeDTO dto) {
        Employee emp = Employee.builder()
                .employeeId(dto.getEmployeeId())
                .fullName(dto.getFullName())
                .department(dto.getDepartment())
                .position(dto.getPosition())
                .email(dto.getEmail())
                .phone(dto.getPhone())
                .isActive(true)
                .build();
        return toDTO(employeeRepository.save(emp));
    }

    @Transactional
    public EmployeeDTO updateEmployee(Long id, EmployeeDTO dto) {
        Employee emp = employeeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        emp.setFullName(dto.getFullName());
        emp.setDepartment(dto.getDepartment());
        emp.setPosition(dto.getPosition());
        emp.setEmail(dto.getEmail());
        emp.setPhone(dto.getPhone());
        emp.setIsActive(dto.getIsActive());
        return toDTO(employeeRepository.save(emp));
    }

    @Transactional
    public void deleteEmployee(Long id) {
        Employee emp = employeeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        emp.setIsActive(false);
        employeeRepository.save(emp);
    }

    @Transactional(readOnly = true)
    public long getTotalCount() {
        return employeeRepository.countByIsActiveTrue();
    }

    private EmployeeDTO toDTO(Employee emp) {
        return EmployeeDTO.builder()
                .id(emp.getId())
                .employeeId(emp.getEmployeeId())
                .fullName(emp.getFullName())
                .department(emp.getDepartment())
                .position(emp.getPosition())
                .email(emp.getEmail())
                .phone(emp.getPhone())
                .faceTemplateId(emp.getFaceTemplateId())
                .faceImageUrl(emp.getFaceImageUrl())
                .isActive(emp.getIsActive())
                .groupId(emp.getGroup() != null ? emp.getGroup().getId() : null)
                .groupName(emp.getGroup() != null ? emp.getGroup().getName() : null)
                .build();
    }
}
