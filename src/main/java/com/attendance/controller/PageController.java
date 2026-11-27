package com.attendance.controller;

import com.attendance.security.AuthenticatedOnly;
import com.attendance.security.PublicEndpoint;
import com.attendance.security.RequiresPermission;
import com.attendance.service.AttendanceService;
import com.attendance.service.CameraService;
import com.attendance.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static com.attendance.security.AppModule.*;
import static com.attendance.security.PermissionAction.VIEW;

@Controller
@RequiredArgsConstructor
public class PageController {

    private final EmployeeService employeeService;
    private final AttendanceService attendanceService;
    private final CameraService cameraService;
    private final com.attendance.security.AccessControlService access;

    @GetMapping("/login")
    @PublicEndpoint
    public String login() {
        return "login";
    }

    @GetMapping("/")
    @AuthenticatedOnly
    public String dashboard(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "dashboard", "page.dashboard");
        com.attendance.security.GroupScope scope = access.scope(ATTENDANCE, VIEW);
        long totalEmployees = scope.isAll()
                ? employeeService.getTotalCount()
                : access.filter(employeeService.getActiveEmployees(), scope,
                        com.attendance.dto.EmployeeDTO::getGroupId).size();
        model.addAttribute("totalEmployees", totalEmployees);
        model.addAttribute("todayDate", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        return "dashboard";
    }

    @GetMapping("/attendance-sync")
    @RequiresPermission(module = ATTENDANCE_SYNC, action = VIEW)
    public String attendanceSync(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "attendance-sync", "page.attendanceSync");
        model.addAttribute("cameras", cameraService.getActiveCameras());
        return "attendance-sync";
    }

    @GetMapping("/attendance")
    @RequiresPermission(module = ATTENDANCE, action = VIEW)
    public String attendance(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "attendance", "page.attendance");
        model.addAttribute("todayDate", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        return "attendance";
    }

    @GetMapping("/employees")
    @RequiresPermission(module = EMPLOYEES, action = VIEW)
    public String employees(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "employees", "page.employees");
        return "employees";
    }

    @GetMapping("/face-library")
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public String faceLibrary(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "face-library", "page.faceLibrary");
        return "face-library";
    }

    @GetMapping("/camera-sync")
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public String cameraSync(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "camera-sync", "page.cameraSync");
        model.addAttribute("cameras", cameraService.getActiveCameras());
        return "camera-sync-enhanced";
    }

    @GetMapping("/face-matches")
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public String faceMatches(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "face-matches", "page.faceMatches");
        return "face-matches";
    }

    @GetMapping("/cameras")
    @RequiresPermission(module = CAMERAS, action = VIEW)
    public String cameras(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "cameras", "page.cameras");
        return "cameras";
    }

    @GetMapping("/reports")
    @RequiresPermission(module = ATTENDANCE, action = VIEW)
    public String reports(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "reports", "page.reports");
        return "reports";
    }

    @GetMapping("/live-view")
    @RequiresPermission(module = CAMERAS, action = VIEW)
    public String liveView(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "live-view", "page.liveView");
        model.addAttribute("cameras", cameraService.getActiveCameras());
        return "live-view";
    }

    @GetMapping("/leaves")
    @RequiresPermission(module = LEAVES, action = VIEW)
    public String leaves(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "leaves", "page.leaves");
        return "leaves";
    }

    @GetMapping("/leave-types")
    @RequiresPermission(module = LEAVES, action = VIEW)
    public String leaveTypes(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "leave-types", "page.leaveTypes");
        return "leave-types";
    }

    @GetMapping("/groups")
    @RequiresPermission(module = GROUPS, action = VIEW)
    public String groups(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "groups", "page.groups");
        return "groups";
    }

    @GetMapping("/shifts")
    @RequiresPermission(module = SHIFTS, action = VIEW)
    public String shifts(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "shifts", "page.shifts");
        return "shifts";
    }

    @GetMapping("/shift-rules")
    @RequiresPermission(module = SHIFTS, action = VIEW)
    public String shiftRules(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "shift-rules", "page.shiftRules");
        return "shift-rules";
    }

    @GetMapping("/shift-schedule")
    @RequiresPermission(module = SHIFT_SCHEDULE, action = VIEW)
    public String shiftSchedule(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "shift-schedule", "page.shiftSchedule");
        return "shift-schedule";
    }

    @GetMapping("/settings")
    @RequiresPermission(module = SETTINGS, action = VIEW)
    public String settings(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "settings", "page.settings");
        return "settings";
    }

    @GetMapping("/users")
    @RequiresPermission(module = USER_MANAGEMENT, action = VIEW)
    public String users(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "users", "page.users");
        return "users";
    }

    @GetMapping("/roles")
    @RequiresPermission(module = USER_MANAGEMENT, action = VIEW)
    public String roles(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "roles", "page.roles");
        return "roles";
    }

    private void addCommonAttributes(Model model, HttpServletRequest request, String activePage, String pageTitleKey) {
        model.addAttribute("activePage", activePage);
        model.addAttribute("pageTitleKey", pageTitleKey);
        model.addAttribute("currentPath", request.getRequestURI());
    }
}
