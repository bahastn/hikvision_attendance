package com.attendance.config;

import com.attendance.entity.*;
import com.attendance.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final EmployeeRepository employeeRepository;
    private final CameraConfigRepository cameraConfigRepository;
    private final LeaveRepository leaveRepository;
    private final ShiftRepository shiftRepository;
    private final EmployeeGroupRepository groupRepository;
    private final ShiftRuleRepository shiftRuleRepository;
    private final ShiftScheduleRepository shiftScheduleRepository;

    @Override
    public void run(String... args) {
        seedShiftsAndGroups();
        seedShiftRules();
        seedShiftSchedules();
        seedEmployees();
        seedCameras();
        seedLeaves();
    }

    private void seedShiftsAndGroups() {
        if (shiftRepository.count() == 0) {
            // Morning Shift
            shiftRepository.save(Shift.builder()
                    .name("Morning Shift")
                    .description("Standard morning shift 8:00 AM - 5:00 PM")
                    .startTime(LocalTime.of(8, 0))
                    .endTime(LocalTime.of(17, 0))
                    .lateThresholdMinutes(15)
                    .earlyLeaveThresholdMinutes(15)
                    .breakStart(LocalTime.of(12, 0))
                    .breakEnd(LocalTime.of(13, 0))
                    .workingDays("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY")
                    .isActive(true)
                    .build());

            // Evening Shift
            shiftRepository.save(Shift.builder()
                    .name("Evening Shift")
                    .description("Evening shift 2:00 PM - 10:00 PM")
                    .startTime(LocalTime.of(14, 0))
                    .endTime(LocalTime.of(22, 0))
                    .lateThresholdMinutes(10)
                    .earlyLeaveThresholdMinutes(10)
                    .breakStart(LocalTime.of(18, 0))
                    .breakEnd(LocalTime.of(19, 0))
                    .workingDays("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY")
                    .isActive(true)
                    .build());

            // Night Shift
            shiftRepository.save(Shift.builder()
                    .name("Night Shift")
                    .description("Night shift 10:00 PM - 6:00 AM")
                    .startTime(LocalTime.of(22, 0))
                    .endTime(LocalTime.of(6, 0))
                    .lateThresholdMinutes(10)
                    .earlyLeaveThresholdMinutes(10)
                    .workingDays("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY")
                    .isActive(true)
                    .build());

            // Weekend Shift
            shiftRepository.save(Shift.builder()
                    .name("Weekend Shift")
                    .description("Weekend shift 9:00 AM - 5:00 PM")
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(17, 0))
                    .lateThresholdMinutes(15)
                    .earlyLeaveThresholdMinutes(15)
                    .workingDays("SATURDAY,SUNDAY")
                    .isActive(true)
                    .build());
        }

        if (groupRepository.count() == 0) {
            List<Shift> shifts = shiftRepository.findAll();
            Shift morningShift = shifts.stream().filter(s -> "Morning Shift".equals(s.getName())).findFirst().orElse(null);
            Shift eveningShift = shifts.stream().filter(s -> "Evening Shift".equals(s.getName())).findFirst().orElse(null);
            Shift nightShift = shifts.stream().filter(s -> "Night Shift".equals(s.getName())).findFirst().orElse(null);

            groupRepository.save(EmployeeGroup.builder()
                    .name("Office Staff")
                    .description("Day office employees working morning shift")
                    .shift(morningShift)
                    .isActive(true)
                    .build());

            groupRepository.save(EmployeeGroup.builder()
                    .name("Support Team")
                    .description("Customer support working evening shift")
                    .shift(eveningShift)
                    .isActive(true)
                    .build());

            groupRepository.save(EmployeeGroup.builder()
                    .name("Security Team")
                    .description("Security personnel on night shift")
                    .shift(nightShift)
                    .isActive(true)
                    .build());
        }
    }

    private void seedShiftRules() {
        if (shiftRuleRepository.count() == 0) {
            shiftRuleRepository.save(ShiftRule.builder()
                    .name("Standard Rule")
                    .description("Standard attendance rules for office staff")
                    .lateThresholdMinutes(15)
                    .earlyLeaveThresholdMinutes(15)
                    .absentAfterMinutes(240)
                    .requireCheckIn(true)
                    .requireCheckOut(true)
                    .gracePeriodMinutes(0)
                    .maxWorkHours(9.0)
                    .isActive(true)
                    .build());

            shiftRuleRepository.save(ShiftRule.builder()
                    .name("Flexible Rule")
                    .description("Flexible rules with longer grace period")
                    .lateThresholdMinutes(30)
                    .earlyLeaveThresholdMinutes(30)
                    .absentAfterMinutes(300)
                    .requireCheckIn(true)
                    .requireCheckOut(true)
                    .gracePeriodMinutes(10)
                    .maxWorkHours(10.0)
                    .isActive(true)
                    .build());

            shiftRuleRepository.save(ShiftRule.builder()
                    .name("Strict Rule")
                    .description("Strict rules with no grace period")
                    .lateThresholdMinutes(5)
                    .earlyLeaveThresholdMinutes(5)
                    .absentAfterMinutes(120)
                    .requireCheckIn(true)
                    .requireCheckOut(true)
                    .gracePeriodMinutes(0)
                    .maxWorkHours(8.0)
                    .isActive(true)
                    .build());

            shiftRuleRepository.save(ShiftRule.builder()
                    .name("Security Rule")
                    .description("Rules for security personnel on night shifts")
                    .lateThresholdMinutes(10)
                    .earlyLeaveThresholdMinutes(10)
                    .absentAfterMinutes(180)
                    .requireCheckIn(true)
                    .requireCheckOut(true)
                    .gracePeriodMinutes(5)
                    .maxWorkHours(10.0)
                    .isActive(true)
                    .build());
        }
    }

    private void seedShiftSchedules() {
        if (shiftScheduleRepository.count() == 0) {
            List<EmployeeGroup> groups = groupRepository.findAll();
            List<Shift> shifts = shiftRepository.findAll();
            List<ShiftRule> rules = shiftRuleRepository.findAll();

            EmployeeGroup officeGroup = groups.stream().filter(g -> "Office Staff".equals(g.getName())).findFirst().orElse(null);
            EmployeeGroup supportGroup = groups.stream().filter(g -> "Support Team".equals(g.getName())).findFirst().orElse(null);
            EmployeeGroup securityGroup = groups.stream().filter(g -> "Security Team".equals(g.getName())).findFirst().orElse(null);

            Shift morningShift = shifts.stream().filter(s -> "Morning Shift".equals(s.getName())).findFirst().orElse(null);
            Shift eveningShift = shifts.stream().filter(s -> "Evening Shift".equals(s.getName())).findFirst().orElse(null);
            Shift nightShift = shifts.stream().filter(s -> "Night Shift".equals(s.getName())).findFirst().orElse(null);

            ShiftRule standardRule = rules.stream().filter(r -> "Standard Rule".equals(r.getName())).findFirst().orElse(null);
            ShiftRule flexibleRule = rules.stream().filter(r -> "Flexible Rule".equals(r.getName())).findFirst().orElse(null);
            ShiftRule securityRule = rules.stream().filter(r -> "Security Rule".equals(r.getName())).findFirst().orElse(null);

            LocalDate today = LocalDate.now();
            LocalDate monthStart = today.withDayOfMonth(1);
            LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());

            if (officeGroup != null && morningShift != null && standardRule != null) {
                shiftScheduleRepository.save(ShiftSchedule.builder()
                        .group(officeGroup)
                        .shift(morningShift)
                        .rule(standardRule)
                        .startDate(monthStart)
                        .endDate(monthEnd)
                        .notes("Standard office hours for this month")
                        .isActive(true)
                        .build());
            }

            if (supportGroup != null && eveningShift != null && flexibleRule != null) {
                shiftScheduleRepository.save(ShiftSchedule.builder()
                        .group(supportGroup)
                        .shift(eveningShift)
                        .rule(flexibleRule)
                        .startDate(monthStart)
                        .endDate(monthEnd)
                        .notes("Evening support with flexible rules")
                        .isActive(true)
                        .build());
            }

            if (securityGroup != null && nightShift != null && securityRule != null) {
                shiftScheduleRepository.save(ShiftSchedule.builder()
                        .group(securityGroup)
                        .shift(nightShift)
                        .rule(securityRule)
                        .startDate(monthStart)
                        .endDate(monthEnd)
                        .notes("Night security patrol")
                        .isActive(true)
                        .build());
            }
        }
    }

    private void seedEmployees() {
        if (employeeRepository.count() == 0) {
            List<EmployeeGroup> groups = groupRepository.findAll();
            EmployeeGroup officeGroup = groups.stream().filter(g -> "Office Staff".equals(g.getName())).findFirst().orElse(null);
            EmployeeGroup supportGroup = groups.stream().filter(g -> "Support Team".equals(g.getName())).findFirst().orElse(null);
            EmployeeGroup securityGroup = groups.stream().filter(g -> "Security Team".equals(g.getName())).findFirst().orElse(null);

            for (int i = 1; i <= 20; i++) {
                EmployeeGroup group = null;
                if (i <= 10 && officeGroup != null) {
                    group = officeGroup;
                } else if (i <= 15 && supportGroup != null) {
                    group = supportGroup;
                } else if (securityGroup != null) {
                    group = securityGroup;
                }

                employeeRepository.save(Employee.builder()
                    .employeeId("EMP" + String.format("%04d", i))
                    .fullName("Employee " + i)
                    .department(i % 3 == 0 ? "IT" : (i % 3 == 1 ? "HR" : "Finance"))
                    .position("Staff")
                    .email("emp" + i + "@company.com")
                    .group(group)
                    .isActive(true)
                    .build());
            }
        }
    }

    private void seedCameras() {
        if (cameraConfigRepository.count() == 0) {
            cameraConfigRepository.save(CameraConfig.builder()
                .cameraName("Main Gate Camera")
                .ipAddress("192.168.1.64")
                .port(80)
                .username("admin")
                .password("admin123")
                .protocol("http")
                .sdkPort(8000)
                .websocketPort(7681)
                .rtspPort(554)
                .location("Main Entrance")
                .isActive(true)
                .build());
        }
    }

    private void seedLeaves() {
        if (leaveRepository.count() == 0) {
            List<Employee> employees = employeeRepository.findAll();
            if (employees.size() < 9) return;

            LocalDate today = LocalDate.now();

            // Employee 0 - Currently on annual leave (started 2 days ago, ends in 3 days)
            leaveRepository.save(Leave.builder()
                    .employee(employees.get(0))
                    .leaveType(Leave.LeaveType.ANNUAL)
                    .startDate(today.minusDays(2))
                    .endDate(today.plusDays(3))
                    .reason("Family vacation")
                    .status(Leave.LeaveStatus.APPROVED)
                    .approvedBy("Admin")
                    .approvedAt(java.time.LocalDateTime.now().minusDays(5))
                    .build());

            // Employee 1 - Currently on sick leave (started today, ends in 2 days)
            leaveRepository.save(Leave.builder()
                    .employee(employees.get(1))
                    .leaveType(Leave.LeaveType.SICK)
                    .startDate(today)
                    .endDate(today.plusDays(2))
                    .reason("Flu and fever")
                    .status(Leave.LeaveStatus.APPROVED)
                    .approvedBy("Admin")
                    .approvedAt(java.time.LocalDateTime.now().minusDays(1))
                    .build());

            // Employee 2 - Currently on casual leave (started 1 day ago, ends today)
            leaveRepository.save(Leave.builder()
                    .employee(employees.get(2))
                    .leaveType(Leave.LeaveType.CASUAL)
                    .startDate(today.minusDays(1))
                    .endDate(today)
                    .reason("Personal matters")
                    .status(Leave.LeaveStatus.APPROVED)
                    .approvedBy("Admin")
                    .approvedAt(java.time.LocalDateTime.now().minusDays(3))
                    .build());

            // Employee 3 - Pending annual leave (starts next week)
            leaveRepository.save(Leave.builder()
                    .employee(employees.get(3))
                    .leaveType(Leave.LeaveType.ANNUAL)
                    .startDate(today.plusDays(7))
                    .endDate(today.plusDays(14))
                    .reason("Summer holiday")
                    .status(Leave.LeaveStatus.PENDING)
                    .build());

            // Employee 4 - Pending sick leave
            leaveRepository.save(Leave.builder()
                    .employee(employees.get(4))
                    .leaveType(Leave.LeaveType.SICK)
                    .startDate(today.plusDays(1))
                    .endDate(today.plusDays(3))
                    .reason("Medical appointment and recovery")
                    .status(Leave.LeaveStatus.PENDING)
                    .build());

            // Employee 5 - Upcoming maternity leave (approved, starts next month)
            leaveRepository.save(Leave.builder()
                    .employee(employees.get(5))
                    .leaveType(Leave.LeaveType.MATERNITY)
                    .startDate(today.plusDays(30))
                    .endDate(today.plusDays(120))
                    .reason("Maternity leave - expecting baby")
                    .status(Leave.LeaveStatus.APPROVED)
                    .approvedBy("Admin")
                    .approvedAt(java.time.LocalDateTime.now().minusDays(10))
                    .build());

            // Employee 6 - Upcoming unpaid leave (approved)
            leaveRepository.save(Leave.builder()
                    .employee(employees.get(6))
                    .leaveType(Leave.LeaveType.UNPAID)
                    .startDate(today.plusDays(10))
                    .endDate(today.plusDays(20))
                    .reason("Personal travel abroad")
                    .status(Leave.LeaveStatus.APPROVED)
                    .approvedBy("Admin")
                    .approvedAt(java.time.LocalDateTime.now().minusDays(2))
                    .build());

            // Employee 7 - Pending casual leave
            leaveRepository.save(Leave.builder()
                    .employee(employees.get(7))
                    .leaveType(Leave.LeaveType.CASUAL)
                    .startDate(today.plusDays(3))
                    .endDate(today.plusDays(4))
                    .reason("Family event")
                    .status(Leave.LeaveStatus.PENDING)
                    .build());

            // Employee 8 - Rejected annual leave
            leaveRepository.save(Leave.builder()
                    .employee(employees.get(8))
                    .leaveType(Leave.LeaveType.ANNUAL)
                    .startDate(today.plusDays(5))
                    .endDate(today.plusDays(10))
                    .reason("Extended vacation")
                    .status(Leave.LeaveStatus.REJECTED)
                    .approvedBy("Admin")
                    .approvedAt(java.time.LocalDateTime.now().minusDays(1))
                    .build());
        }
    }
}
