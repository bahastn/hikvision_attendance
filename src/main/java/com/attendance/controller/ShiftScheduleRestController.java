package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.ShiftScheduleDTO;
import com.attendance.service.ShiftScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/shift-schedules")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ShiftScheduleRestController {

    private final ShiftScheduleService scheduleService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ShiftScheduleDTO>>> getAllSchedules() {
        return ResponseEntity.ok(ApiResponse.success(scheduleService.getAllSchedules()));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<ShiftScheduleDTO>>> getActiveSchedules() {
        return ResponseEntity.ok(ApiResponse.success(scheduleService.getActiveSchedules()));
    }

    @GetMapping("/upcoming-active")
    public ResponseEntity<ApiResponse<List<ShiftScheduleDTO>>> getUpcomingAndActiveSchedules() {
        return ResponseEntity.ok(ApiResponse.success(scheduleService.getUpcomingAndActiveSchedules()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ShiftScheduleDTO>> getSchedule(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(scheduleService.getScheduleById(id)));
    }

    @GetMapping("/group/{groupId}")
    public ResponseEntity<ApiResponse<List<ShiftScheduleDTO>>> getSchedulesByGroup(@PathVariable Long groupId) {
        return ResponseEntity.ok(ApiResponse.success(scheduleService.getSchedulesByGroup(groupId)));
    }

    @GetMapping("/date/{date}")
    public ResponseEntity<ApiResponse<List<ShiftScheduleDTO>>> getSchedulesByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(scheduleService.getSchedulesByDate(date)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ShiftScheduleDTO>> createSchedule(@RequestBody ShiftScheduleDTO dto) {
        log.info("Creating shift schedule: group={} shift={} rule={} dates={} to {}",
                dto.getGroupId(), dto.getShiftId(), dto.getRuleId(), dto.getStartDate(), dto.getEndDate());
        return ResponseEntity.ok(ApiResponse.success("Shift schedule created", scheduleService.createSchedule(dto)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ShiftScheduleDTO>> updateSchedule(@PathVariable Long id, @RequestBody ShiftScheduleDTO dto) {
        log.info("Updating shift schedule {}: group={} shift={} dates={} to {}",
                id, dto.getGroupId(), dto.getShiftId(), dto.getStartDate(), dto.getEndDate());
        return ResponseEntity.ok(ApiResponse.success("Shift schedule updated", scheduleService.updateSchedule(id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteSchedule(@PathVariable Long id) {
        scheduleService.deleteSchedule(id);
        return ResponseEntity.ok(ApiResponse.success("Shift schedule deleted", null));
    }
}
