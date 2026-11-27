package com.attendance.service;

import com.attendance.dto.LeaveDTO;
import com.attendance.entity.Employee;
import com.attendance.entity.Leave;
import com.attendance.entity.LeaveType;
import com.attendance.repository.EmployeeRepository;
import com.attendance.repository.LeaveRepository;
import com.attendance.repository.LeaveTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveService {

    private final LeaveRepository leaveRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveTypeRepository leaveTypeRepository;

    @Value("${attendance.leave-storage-dir:leave-attachments}")
    private String leaveStorageDir;

    /** Content types accepted as leave attachments, mapped to the file extension we store them under. */
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/gif", "gif",
            "application/pdf", "pdf"
    );

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
    public List<LeaveDTO> getLeavesByType(Long leaveTypeId) {
        return leaveRepository.findByLeaveTypeId(leaveTypeId).stream()
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

    @Transactional(readOnly = true)
    public LeaveDTO getLeave(Long id) {
        return toDTO(leaveRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Leave not found")));
    }

    @Transactional
    public LeaveDTO createLeave(LeaveDTO dto, MultipartFile file) {
        if (dto.getEmployeeDbId() == null) {
            throw new IllegalArgumentException("Employee is required");
        }
        if (dto.getStartDate() == null || dto.getEndDate() == null || dto.getEndDate().isBefore(dto.getStartDate())) {
            throw new IllegalArgumentException("End date must be on or after the start date");
        }
        if (dto.getLeaveTypeId() == null) {
            throw new IllegalArgumentException("Leave type is required");
        }
        Employee employee = employeeRepository.findById(dto.getEmployeeDbId())
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        LeaveType leaveType = leaveTypeRepository.findById(dto.getLeaveTypeId())
                .orElseThrow(() -> new IllegalArgumentException("Leave type not found"));
        if (!Boolean.TRUE.equals(leaveType.getIsActive())) {
            throw new IllegalArgumentException("Leave type is not active");
        }

        Leave leave = Leave.builder()
                .employee(employee)
                .leaveType(leaveType)
                .startDate(dto.getStartDate())
                .endDate(dto.getEndDate())
                .reason(dto.getReason())
                .status(Leave.LeaveStatus.PENDING)
                .build();

        // Save first so the row has an id to name the attachment file after.
        leave = leaveRepository.save(leave);
        if (file != null && !file.isEmpty()) {
            storeAttachment(leave, file);
            leave = leaveRepository.save(leave);
        }
        return toDTO(leave);
    }

    /**
     * Update an editable leave (PENDING or APPROVED). Dates/type/reason are replaced; the status is
     * kept as-is (editing an approved leave keeps it approved). A new file replaces any existing
     * attachment; {@code removeAttachment} deletes the current one when no replacement is given.
     */
    @Transactional
    public LeaveDTO updateLeave(Long id, LeaveDTO dto, MultipartFile file, boolean removeAttachment) {
        Leave leave = leaveRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Leave not found"));
        if (leave.getStatus() != Leave.LeaveStatus.PENDING && leave.getStatus() != Leave.LeaveStatus.APPROVED) {
            throw new IllegalArgumentException("Only pending or approved leaves can be edited");
        }
        if (dto.getStartDate() == null || dto.getEndDate() == null || dto.getEndDate().isBefore(dto.getStartDate())) {
            throw new IllegalArgumentException("End date must be on or after the start date");
        }
        if (dto.getLeaveTypeId() != null
                && (leave.getLeaveType() == null || !dto.getLeaveTypeId().equals(leave.getLeaveType().getId()))) {
            LeaveType leaveType = leaveTypeRepository.findById(dto.getLeaveTypeId())
                    .orElseThrow(() -> new IllegalArgumentException("Leave type not found"));
            if (!Boolean.TRUE.equals(leaveType.getIsActive())) {
                throw new IllegalArgumentException("Leave type is not active");
            }
            leave.setLeaveType(leaveType);
        }
        leave.setStartDate(dto.getStartDate());
        leave.setEndDate(dto.getEndDate());
        leave.setReason(dto.getReason());

        if (file != null && !file.isEmpty()) {
            deleteAttachmentFile(leave);
            storeAttachment(leave, file);
        } else if (removeAttachment) {
            deleteAttachmentFile(leave);
            leave.setAttachmentPath(null);
            leave.setAttachmentFilename(null);
            leave.setAttachmentContentType(null);
        }
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
        leaveRepository.findById(id).ifPresent(leave -> {
            deleteAttachmentFile(leave);
            leaveRepository.delete(leave);
        });
    }

    // ------------------------------------------------------------------ attachments

    /** A leave's attachment resolved for streaming: raw bytes plus how to present them. */
    public record AttachmentResource(byte[] data, String contentType, String filename) {}

    @Transactional(readOnly = true)
    public AttachmentResource loadAttachment(Long id) {
        Leave leave = leaveRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Leave not found"));
        if (leave.getAttachmentPath() == null) {
            return null;
        }
        try {
            Path file = resolveAttachmentPath(leave.getAttachmentPath());
            if (file == null || !Files.isRegularFile(file)) {
                return null;
            }
            String contentType = leave.getAttachmentContentType() != null
                    ? leave.getAttachmentContentType() : "application/octet-stream";
            return new AttachmentResource(Files.readAllBytes(file), contentType, leave.getAttachmentFilename());
        } catch (IOException e) {
            log.warn("Failed to read leave attachment for leave {}: {}", id, e.getMessage());
            return null;
        }
    }

    /** Validate, write the uploaded file to disk and set the leave's attachment fields (does not save). */
    private void storeAttachment(Leave leave, MultipartFile file) {
        String contentType = file.getContentType();
        String ext = contentType == null ? null : ALLOWED_TYPES.get(contentType.toLowerCase());
        if (ext == null) {
            throw new IllegalArgumentException("Unsupported file type. Allowed: JPG, PNG, GIF, PDF");
        }
        try {
            Path dir = Paths.get(leaveStorageDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String filename = "leave_" + leave.getId() + "_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    + "_" + ThreadLocalRandom.current().nextInt(1000) + "." + ext;
            Files.write(dir.resolve(filename), file.getBytes());
            leave.setAttachmentPath(filename);
            leave.setAttachmentContentType(contentType.toLowerCase());
            leave.setAttachmentFilename(sanitizeFilename(file.getOriginalFilename(), ext));
        } catch (IOException e) {
            throw new RuntimeException("Failed to store attachment: " + e.getMessage(), e);
        }
    }

    /** Delete the leave's attachment file from disk, if any. Leaves the entity fields untouched. */
    private void deleteAttachmentFile(Leave leave) {
        if (leave.getAttachmentPath() == null) {
            return;
        }
        try {
            Path file = resolveAttachmentPath(leave.getAttachmentPath());
            if (file != null) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            log.warn("Failed to delete leave attachment {}: {}", leave.getAttachmentPath(), e.getMessage());
        }
    }

    /** Resolve a stored relative filename to an absolute path, guarding against path traversal. */
    private Path resolveAttachmentPath(String storedName) {
        Path base = Paths.get(leaveStorageDir).toAbsolutePath().normalize();
        Path resolved = base.resolve(storedName).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }

    private static String sanitizeFilename(String original, String ext) {
        if (original == null || original.isBlank()) {
            return "attachment." + ext;
        }
        // Keep only the base name; drop any path components a browser might include.
        String base = original.replaceAll("[\\\\/]+", "_").trim();
        return base.length() > 200 ? base.substring(base.length() - 200) : base;
    }

    private LeaveDTO toDTO(Leave leave) {
        int totalDays = (int) ChronoUnit.DAYS.between(leave.getStartDate(), leave.getEndDate()) + 1;
        return LeaveDTO.builder()
                .id(leave.getId())
                .employeeDbId(leave.getEmployee().getId())
                .groupId(leave.getEmployee().getGroup() != null ? leave.getEmployee().getGroup().getId() : null)
                .employeeId(leave.getEmployee().getEmployeeId())
                .employeeName(leave.getEmployee().getFullName())
                .department(leave.getEmployee().getDepartment())
                .leaveTypeId(leave.getLeaveType() != null ? leave.getLeaveType().getId() : null)
                .leaveTypeCode(leave.getLeaveType() != null ? leave.getLeaveType().getCode() : null)
                .leaveTypeName(LeaveTypeService.localizedName(leave.getLeaveType()))
                .startDate(leave.getStartDate())
                .endDate(leave.getEndDate())
                .totalDays(totalDays)
                .reason(leave.getReason())
                .attachmentUrl(leave.getAttachmentPath() != null ? "/api/leaves/" + leave.getId() + "/attachment" : null)
                .attachmentFilename(leave.getAttachmentFilename())
                .attachmentContentType(leave.getAttachmentContentType())
                .status(leave.getStatus().name())
                .approvedBy(leave.getApprovedBy())
                .approvedAt(leave.getApprovedAt())
                .createdAt(leave.getCreatedAt())
                .build();
    }
}
