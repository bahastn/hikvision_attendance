package com.attendance.service;

import com.attendance.dto.LeaveTypeDTO;
import com.attendance.entity.LeaveType;
import com.attendance.repository.LeaveRepository;
import com.attendance.repository.LeaveTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeaveTypeService {

    private final LeaveTypeRepository leaveTypeRepository;
    private final LeaveRepository leaveRepository;

    @Transactional(readOnly = true)
    public List<LeaveTypeDTO> getAllLeaveTypes() {
        return leaveTypeRepository.findAllByOrderByDisplayOrderAscNameEnAsc().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveTypeDTO> getActiveLeaveTypes() {
        return leaveTypeRepository.findByIsActiveTrueOrderByDisplayOrderAscNameEnAsc().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public LeaveTypeDTO createLeaveType(LeaveTypeDTO dto) {
        String nameEn = requireText(dto.getNameEn(), "English name is required");
        String code = generateUniqueCode(nameEn);
        LeaveType type = LeaveType.builder()
                .code(code)
                .nameEn(nameEn)
                .nameKu(blankToNull(dto.getNameKu()))
                .displayOrder(dto.getDisplayOrder() != null ? dto.getDisplayOrder() : nextDisplayOrder())
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : Boolean.TRUE)
                .build();
        return toDTO(leaveTypeRepository.save(type));
    }

    @Transactional
    public LeaveTypeDTO updateLeaveType(Long id, LeaveTypeDTO dto) {
        LeaveType type = leaveTypeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Leave type not found"));
        type.setNameEn(requireText(dto.getNameEn(), "English name is required"));
        type.setNameKu(blankToNull(dto.getNameKu()));
        if (dto.getDisplayOrder() != null) type.setDisplayOrder(dto.getDisplayOrder());
        if (dto.getIsActive() != null) type.setIsActive(dto.getIsActive());
        // code is immutable once created to keep historical references stable
        return toDTO(leaveTypeRepository.save(type));
    }

    /** Soft delete: deactivate so existing leaves keep their type but it leaves the request dropdown. */
    @Transactional
    public void deactivateLeaveType(Long id) {
        LeaveType type = leaveTypeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Leave type not found"));
        type.setIsActive(false);
        leaveTypeRepository.save(type);
    }

    /** Permanent delete, only allowed when no leave references the type. */
    @Transactional
    public void permanentlyDeleteLeaveType(Long id) {
        LeaveType type = leaveTypeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Leave type not found"));
        long usage = leaveRepository.countByLeaveTypeId(id);
        if (usage > 0) {
            throw new IllegalStateException("Leave type is used by " + usage + " leave record(s)");
        }
        leaveTypeRepository.delete(type);
    }

    private int nextDisplayOrder() {
        return leaveTypeRepository.findAll().stream()
                .map(LeaveType::getDisplayOrder)
                .filter(o -> o != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private String generateUniqueCode(String nameEn) {
        String root = nameEn.trim().toUpperCase()
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (root.isBlank()) root = "TYPE";
        if (root.length() > 45) root = root.substring(0, 45);
        String code = root;
        int n = 2;
        while (leaveTypeRepository.existsByCodeIgnoreCase(code)) {
            code = root + "_" + n;
            n++;
        }
        return code;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private LeaveTypeDTO toDTO(LeaveType type) {
        return LeaveTypeDTO.builder()
                .id(type.getId())
                .code(type.getCode())
                .nameEn(type.getNameEn())
                .nameKu(type.getNameKu())
                .name(localizedName(type))
                .displayOrder(type.getDisplayOrder())
                .isActive(type.getIsActive())
                .usageCount(leaveRepository.countByLeaveTypeId(type.getId()))
                .build();
    }

    /**
     * Label to show for the current request locale: Kurdish name when the UI is in
     * Kurdish and a Kurdish name exists, English name otherwise, code as last resort.
     */
    public static String localizedName(LeaveType type) {
        if (type == null) return null;
        boolean kurdish = "ku".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage());
        if (kurdish && type.getNameKu() != null && !type.getNameKu().isBlank()) {
            return type.getNameKu();
        }
        if (type.getNameEn() != null && !type.getNameEn().isBlank()) {
            return type.getNameEn();
        }
        return type.getCode();
    }
}
