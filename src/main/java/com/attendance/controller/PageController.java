package com.attendance.controller;

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

@Controller
@RequiredArgsConstructor
public class PageController {

    private final EmployeeService employeeService;
    private final AttendanceService attendanceService;
    private final CameraService cameraService;

    @GetMapping("/")
    public String dashboard(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "dashboard", "page.dashboard");
        model.addAttribute("totalEmployees", employeeService.getTotalCount());
        model.addAttribute("todayDate", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        model.addAttribute("cameras", cameraService.getActiveCameras());
        return "dashboard";
    }

    @GetMapping("/attendance")
    public String attendance(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "attendance", "page.attendance");
        model.addAttribute("todayDate", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        return "attendance";
    }

    @GetMapping("/employees")
    public String employees(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "employees", "page.employees");
        return "employees";
    }

    @GetMapping("/face-library")
    public String faceLibrary(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "face-library", "page.faceLibrary");
        return "face-library";
    }

    @GetMapping("/cameras")
    public String cameras(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "cameras", "page.cameras");
        return "cameras";
    }

    @GetMapping("/reports")
    public String reports(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "reports", "page.reports");
        return "reports";
    }

    @GetMapping("/live-view")
    public String liveView(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "live-view", "page.liveView");
        model.addAttribute("cameras", cameraService.getActiveCameras());
        return "live-view";
    }

    @GetMapping("/leaves")
    public String leaves(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "leaves", "page.leaves");
        return "leaves";
    }

    @GetMapping("/groups")
    public String groups(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "groups", "page.groups");
        return "groups";
    }

    @GetMapping("/shifts")
    public String shifts(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "shifts", "page.shifts");
        return "shifts";
    }

    @GetMapping("/shift-rules")
    public String shiftRules(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "shift-rules", "page.shiftRules");
        return "shift-rules";
    }

    @GetMapping("/shift-schedule")
    public String shiftSchedule(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "shift-schedule", "page.shiftSchedule");
        return "shift-schedule";
    }

    @GetMapping("/settings")
    public String settings(Model model, HttpServletRequest request) {
        addCommonAttributes(model, request, "settings", "page.settings");
        return "settings";
    }

    private void addCommonAttributes(Model model, HttpServletRequest request, String activePage, String pageTitleKey) {
        model.addAttribute("activePage", activePage);
        model.addAttribute("pageTitleKey", pageTitleKey);
        model.addAttribute("currentPath", request.getRequestURI());
    }
}
