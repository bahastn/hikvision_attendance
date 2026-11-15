package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.ShiftRuleDTO;
import com.attendance.service.ShiftRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/shift-rules")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ShiftRuleRestController {

    private final ShiftRuleService ruleService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ShiftRuleDTO>>> getAllRules() {
        return ResponseEntity.ok(ApiResponse.success(ruleService.getAllRules()));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<ShiftRuleDTO>>> getActiveRules() {
        return ResponseEntity.ok(ApiResponse.success(ruleService.getActiveRules()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ShiftRuleDTO>> getRule(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(ruleService.getRuleById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ShiftRuleDTO>> createRule(@RequestBody ShiftRuleDTO dto) {
        log.info("Creating shift rule: name='{}' late={}min early={}min absent={}min",
                dto.getName(), dto.getLateThresholdMinutes(), dto.getEarlyLeaveThresholdMinutes(), dto.getAbsentAfterMinutes());
        return ResponseEntity.ok(ApiResponse.success("Shift rule created", ruleService.createRule(dto)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ShiftRuleDTO>> updateRule(@PathVariable Long id, @RequestBody ShiftRuleDTO dto) {
        log.info("Updating shift rule {}: name='{}'", id, dto.getName());
        return ResponseEntity.ok(ApiResponse.success("Shift rule updated", ruleService.updateRule(id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteRule(@PathVariable Long id) {
        ruleService.deleteRule(id);
        return ResponseEntity.ok(ApiResponse.success("Shift rule deleted", null));
    }

    /**
     * Permanently delete a shift rule. This removes the rule from all groups and schedules, then deletes the record.
     * This action cannot be undone.
     */
    @DeleteMapping("/{id}/permanent")
    public ResponseEntity<ApiResponse<Void>> permanentlyDeleteRule(@PathVariable Long id) {
        try {
            ruleService.permanentlyDeleteRule(id);
            return ResponseEntity.ok(ApiResponse.success("Shift rule permanently deleted", null));
        } catch (Exception e) {
            log.warn("Permanent delete failed for shift rule {}: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Delete failed: " + e.getMessage()));
        }
    }
}
