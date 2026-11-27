package com.attendance.service;

import com.attendance.dto.EmployeeDTO;
import com.attendance.entity.Employee;
import com.attendance.entity.EmployeeCategory;
import com.attendance.entity.EmployeeGroup;
import com.attendance.repository.EmployeeCameraTargetRepository;
import com.attendance.repository.EmployeeGroupRepository;
import static com.attendance.security.TextSanitizer.clean;
import com.attendance.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeCameraTargetRepository targetRepository;
    private final EmployeeGroupRepository groupRepository;
    private final FaceEnrollmentService faceEnrollmentService;

    public EmployeeService(EmployeeRepository employeeRepository,
                           EmployeeCameraTargetRepository targetRepository,
                           EmployeeGroupRepository groupRepository,
                           @Lazy FaceEnrollmentService faceEnrollmentService) {
        this.employeeRepository = employeeRepository;
        this.targetRepository = targetRepository;
        this.groupRepository = groupRepository;
        this.faceEnrollmentService = faceEnrollmentService;
    }

    private EmployeeGroup findGroup(Long groupId) {
        if (groupId == null) {
            return null;
        }
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));
    }

    @Transactional(readOnly = true)
    public List<EmployeeDTO> getAllEmployees() {
        Map<Long, List<String>> camerasByEmployee = camerasByEmployee();
        return employeeRepository.findAll().stream()
                .map(emp -> toDTO(emp, camerasByEmployee.getOrDefault(emp.getId(), List.of())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmployeeDTO> getActiveEmployees() {
        Map<Long, List<String>> camerasByEmployee = camerasByEmployee();
        return employeeRepository.findByIsActiveTrue().stream()
                .map(emp -> toDTO(emp, camerasByEmployee.getOrDefault(emp.getId(), List.of())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EmployeeDTO getEmployeeById(Long id) {
        Employee emp = employeeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        List<String> faceCameras = targetRepository.findByEmployeeId(id).stream()
                .map(t -> t.getCamera().getCameraName())
                .collect(Collectors.toList());
        return toDTO(emp, faceCameras);
    }

    /** Names of the cameras each employee's face is enrolled on, grouped by employee id. */
    private Map<Long, List<String>> camerasByEmployee() {
        return targetRepository.findAll().stream()
                .collect(Collectors.groupingBy(
                        t -> t.getEmployee().getId(),
                        Collectors.mapping(t -> t.getCamera().getCameraName(), Collectors.toList())));
    }

    private EmployeeCategory parseCategory(String value) {
        if (value == null || value.isBlank()) {
            return EmployeeCategory.EMPLOYEE;
        }
        try {
            return EmployeeCategory.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid employee category. Allowed values: EMPLOYEE, MANAGER, ADVISER");
        }
    }

    @Transactional
    public EmployeeDTO createEmployee(EmployeeDTO dto) {
        employeeRepository.findByEmployeeId(dto.getEmployeeId()).ifPresent(e -> {
            throw new RuntimeException("Employee ID '" + dto.getEmployeeId() + "' already exists");
        });
        Employee emp = Employee.builder()
                .employeeId(clean("Employee ID", dto.getEmployeeId()))
                .fullName(clean("Full name", dto.getFullName()))
                .fullNameKurdish(clean("Full name (Kurdish)", dto.getFullNameKurdish()))
                .department(clean("Department", dto.getDepartment()))
                .position(clean("Position", dto.getPosition()))
                .category(parseCategory(dto.getCategory()))
                .email(clean("Email", dto.getEmail()))
                .phone(clean("Phone", dto.getPhone()))
                .isActive(true)
                .group(findGroup(dto.getGroupId()))
                .build();
        return toDTO(employeeRepository.save(emp));
    }

    @Transactional
    public EmployeeDTO updateEmployee(Long id, EmployeeDTO dto) {
        Employee emp = employeeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        emp.setFullName(clean("Full name", dto.getFullName()));
        emp.setFullNameKurdish(clean("Full name (Kurdish)", dto.getFullNameKurdish()));
        emp.setDepartment(clean("Department", dto.getDepartment()));
        emp.setPosition(clean("Position", dto.getPosition()));
        if (dto.getCategory() != null && !dto.getCategory().isBlank()) {
            emp.setCategory(parseCategory(dto.getCategory()));
        } else if (emp.getCategory() == null) {
            emp.setCategory(EmployeeCategory.EMPLOYEE);
        }
        emp.setEmail(clean("Email", dto.getEmail()));
        emp.setPhone(clean("Phone", dto.getPhone()));
        // The edit form does not send isActive (activation has its own endpoints); keep the current value.
        if (dto.getIsActive() != null) {
            emp.setIsActive(dto.getIsActive());
        }
        // Only move the employee when a group is given; removing from a group is done on the Groups page.
        if (dto.getGroupId() != null) {
            emp.setGroup(findGroup(dto.getGroupId()));
        }
        return toDTO(employeeRepository.save(emp));
    }

    @Transactional
    public void deleteEmployee(Long id) {
        Employee emp = employeeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        emp.setIsActive(false);
        employeeRepository.save(emp);
    }

    /**
     * Activate an employee: sets isActive=true and pushes the stored face photo
     * to every active camera so the person can be recognised again.
     */
    @Transactional
    public EmployeeDTO activateEmployee(Long id) {
        Employee emp = employeeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        emp.setIsActive(true);
        employeeRepository.save(emp);

        // Push the face back to cameras that don't have it yet
        try {
            faceEnrollmentService.enrollFace(id, null, List.of());
        } catch (Exception e) {
            log.warn("Could not re-enroll face of employee {} on activation: {}", id, e.getMessage());
        }
        return toDTO(emp);
    }

    /**
     * Deactivate an employee: sets isActive=false and removes the face from
     * every camera's face library so the person is no longer recognised.
     */
    @Transactional
    public EmployeeDTO deactivateEmployee(Long id) {
        Employee emp = employeeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        emp.setIsActive(false);
        employeeRepository.save(emp);

        try {
            faceEnrollmentService.removeFace(id);
        } catch (Exception e) {
            log.warn("Could not remove face of employee {} on deactivation: {}", id, e.getMessage());
        }
        return toDTO(emp);
    }

    /**
     * Permanently delete an employee: removes the face from all cameras,
     * deletes camera-target mappings and the employee record itself.
     */
    @Transactional
    public void permanentlyDeleteEmployee(Long id) {
        Employee emp = employeeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        try {
            faceEnrollmentService.removeFace(id);
        } catch (Exception e) {
            log.warn("Could not remove face of employee {} before permanent delete: {}", id, e.getMessage());
        }
        targetRepository.deleteByEmployeeId(id);
        employeeRepository.delete(emp);
    }

    @Transactional(readOnly = true)
    public long getTotalCount() {
        return employeeRepository.countByIsActiveTrue();
    }

    private EmployeeDTO toDTO(Employee emp) {
        return toDTO(emp, List.of());
    }

    private EmployeeDTO toDTO(Employee emp, List<String> faceCameras) {
        return EmployeeDTO.builder()
                .id(emp.getId())
                .employeeId(emp.getEmployeeId())
                .fullName(emp.getFullName())
                .fullNameKurdish(emp.getFullNameKurdish())
                .department(emp.getDepartment())
                .position(emp.getPosition())
                .category(emp.getCategory() != null ? emp.getCategory().name() : EmployeeCategory.EMPLOYEE.name())
                .email(emp.getEmail())
                .phone(emp.getPhone())
                .faceTemplateId(emp.getFaceTemplateId())
                .faceImageUrl(emp.getFaceImageUrl())
                .isActive(emp.getIsActive())
                .groupId(emp.getGroup() != null ? emp.getGroup().getId() : null)
                .groupName(emp.getGroup() != null ? emp.getGroup().getName() : null)
                .faceCameras(faceCameras)
                .syncStatus(emp.getSyncStatus() != null ? emp.getSyncStatus().name() : null)
                .lastCameraSync(emp.getLastCameraSync())
                .cameraSyncCount(emp.getCameraSyncCount())
                .build();
    }
}
