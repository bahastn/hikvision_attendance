package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.SecurityDtos.CurrentUserDTO;
import com.attendance.dto.SecurityDtos.PasswordChangeRequest;
import com.attendance.security.*;
import com.attendance.service.UserManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/** The signed-in user's own account: password change and "who am I". */
@Controller
@RequiredArgsConstructor
public class AccountController {

    private final UserManagementService userManagementService;
    private final AccessControlService access;
    private final PasswordPolicy passwordPolicy;

    @GetMapping("/account/password")
    @AuthenticatedOnly
    @AllowDuringPasswordChange
    public String passwordPage(Model model, HttpServletRequest request) {
        model.addAttribute("activePage", "account");
        model.addAttribute("pageTitleKey", "page.changePassword");
        model.addAttribute("currentPath", request.getRequestURI());
        model.addAttribute("mustChangePassword", access.current().mustChangePassword());
        model.addAttribute("passwordMinLength", passwordPolicy.minLength());
        return "account-password";
    }

    @PostMapping("/api/account/password")
    @ResponseBody
    @AuthenticatedOnly
    @AllowDuringPasswordChange
    public ResponseEntity<ApiResponse<Void>> changePassword(@Valid @RequestBody PasswordChangeRequest body,
                                                            HttpServletRequest request) {
        LocalDateTime changedAt = userManagementService.changeOwnPassword(
                body.getCurrentPassword(), body.getNewPassword());
        // Stamp this session with exactly the change time so it is not seen as pre-dating it;
        // every other session of the user predates the change and ends.
        request.getSession().setAttribute(PermissionInterceptor.SESSION_AUTH_TIME, changedAt);
        return ResponseEntity.ok(ApiResponse.success("Password changed", null));
    }

    @GetMapping("/api/me")
    @ResponseBody
    @AuthenticatedOnly
    @AllowDuringPasswordChange
    public ResponseEntity<ApiResponse<CurrentUserDTO>> me() {
        EffectivePermissions p = access.current();
        return ResponseEntity.ok(ApiResponse.success(CurrentUserDTO.builder()
                .username(p.username())
                .fullName(p.fullName())
                .superAdmin(p.superAdmin())
                .mustChangePassword(p.mustChangePassword())
                .permissions(p.toClientMap())
                .build()));
    }
}
