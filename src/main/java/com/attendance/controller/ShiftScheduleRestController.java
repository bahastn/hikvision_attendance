package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.ShiftScheduleDTO;
import com.attendance.security.AccessControlService;
import com.attendance.security.RequiresPermission;
import com.attendance.service.ShiftScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

import static com.attendance.security.AppModule.SHIFT_SCHEDULE;
import static com.attendance.security.PermissionAction.*;

@RestController
@RequestMapping("/api/shift-schedules")
@RequiredArgsConstructor
@Slf4j
public class ShiftScheduleRestController {

    private final ShiftScheduleService scheduleService;
    private final AccessControlService access;

    @GetMapping
    @RequiresPermission(module = SHIFT_SCHEDULE, action = VIEW)
    public ResponseEntity<ApiResponse<List<ShiftScheduleDTO>>> getAllSchedules() {
        return ok(scheduleService.getAllSchedules());
    }

    @GetMapping("/active")
    @RequiresPermission(module = SHIFT_SCHEDULE, action = VIEW)
    public ResponseEntity<ApiResponse<List<ShiftScheduleDTO>>> getActiveSchedules() {
        return ok(scheduleService.getActiveSchedules());
    }

    @GetMapping("/upcoming-active")
    @RequiresPermission(module = SHIFT_SCHEDULE, action = VIEW)
    public ResponseEntity<ApiResponse<List<ShiftScheduleDTO>>> getUpcomingAndActiveSchedules() {
        return ok(scheduleService.getUpcomingAndActiveSchedules());
    }

    @GetMapping("/{id}")
    @RequiresPermission(module = SHIFT_SCHEDULE, action = VIEW)
    public ResponseEntity<ApiResponse<ShiftScheduleDTO>> getSchedule(@PathVariable Long id) {
        access.requireSchedule(VIEW, id);
        return ResponseEntity.ok(ApiResponse.success(scheduleService.getScheduleById(id)));
    }

    @GetMapping("/group/{groupId}")
    @RequiresPermission(module = SHIFT_SCHEDULE, action = VIEW)
    public ResponseEntity<ApiResponse<List<ShiftScheduleDTO>>> getSchedulesByGroup(@PathVariable Long groupId) {
        access.requireGroup(SHIFT_SCHEDULE, VIEW, groupId);
        return ResponseEntity.ok(ApiResponse.success(scheduleService.getSchedulesByGroup(groupId)));
    }

    @GetMapping("/date/{date}")
    @RequiresPermission(module = SHIFT_SCHEDULE, action = VIEW)
    public ResponseEntity<ApiResponse<List<ShiftScheduleDTO>>> getSchedulesByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ok(scheduleService.getSchedulesByDate(date));
    }

    @PostMapping
    @RequiresPermission(module = SHIFT_SCHEDULE, action = MODIFY)
    public ResponseEntity<ApiResponse<ShiftScheduleDTO>> createSchedule(@RequestBody ShiftScheduleDTO dto) {
        access.requireGroup(SHIFT_SCHEDULE, MODIFY, dto.getGroupId());
        log.info("Creating shift schedule: group={} shift={} rule={} dates={} to {} by {}",
                dto.getGroupId(), dto.getShiftId(), dto.getRuleId(), dto.getStartDate(), dto.getEndDate(),
                access.currentUsername());
        return ResponseEntity.ok(ApiResponse.success("Shift schedule created", scheduleService.createSchedule(dto)));
    }

    @PutMapping("/{id}")
    @RequiresPermission(module = SHIFT_SCHEDULE, action = MODIFY)
    public ResponseEntity<ApiResponse<ShiftScheduleDTO>> updateSchedule(@PathVariable Long id, @RequestBody ShiftScheduleDTO dto) {
        access.requireSchedule(MODIFY, id);
        access.requireGroup(SHIFT_SCHEDULE, MODIFY, dto.getGroupId());
        log.info("Updating shift schedule {}: group={} shift={} dates={} to {} by {}",
                id, dto.getGroupId(), dto.getShiftId(), dto.getStartDate(), dto.getEndDate(), access.currentUsername());
        return ResponseEntity.ok(ApiResponse.success("Shift schedule updated", scheduleService.updateSchedule(id, dto)));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(module = SHIFT_SCHEDULE, action = DELETE)
    public ResponseEntity<ApiResponse<Void>> deleteSchedule(@PathVariable Long id) {
        access.requireSchedule(DELETE, id);
        scheduleService.deleteSchedule(id);
        return ResponseEntity.ok(ApiResponse.success("Shift schedule deleted", null));
    }

    private ResponseEntity<ApiResponse<List<ShiftScheduleDTO>>> ok(List<ShiftScheduleDTO> schedules) {
        return ResponseEntity.ok(ApiResponse.success(
                access.filter(schedules, SHIFT_SCHEDULE, VIEW, ShiftScheduleDTO::getGroupId)));
    }
}
