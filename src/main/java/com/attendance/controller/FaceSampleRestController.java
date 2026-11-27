package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.security.AccessControlService;
import com.attendance.security.RequiresPermission;
import com.attendance.service.insightface.FaceSampleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.attendance.security.AppModule.EMPLOYEES;
import static com.attendance.security.PermissionAction.MODIFY;
import static com.attendance.security.PermissionAction.VIEW;

/**
 * Manage an employee's reference face photos for InsightFace (several per employee). The
 * enrollment photo (the one on the cameras) is listed too but is changed via the employee form.
 */
@RestController
@RequestMapping("/api/employees/{employeeId}/face-samples")
@RequiredArgsConstructor
@Slf4j
public class FaceSampleRestController {

    private final FaceSampleService sampleService;
    private final AccessControlService access;

    @GetMapping
    @RequiresPermission(module = EMPLOYEES, action = VIEW)
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(@PathVariable Long employeeId) {
        access.requireEmployee(EMPLOYEES, VIEW, employeeId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", sampleService.isEnabled());
        body.put("samples", sampleService.listSamples(employeeId));
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    /**
     * Upload one or more photos (multipart field {@code photos}, repeatable). Each photo is checked
     * independently; the response lists the outcome per file. {@code force=true} skips the
     * "looks like another employee" check (quality and duplicate checks still apply).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(module = EMPLOYEES, action = MODIFY)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> upload(
            @PathVariable Long employeeId,
            @RequestParam("photos") List<MultipartFile> photos,
            @RequestParam(defaultValue = "false") boolean force) {
        access.requireEmployee(EMPLOYEES, MODIFY, employeeId);
        if (photos == null || photos.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Please choose at least one photo"));
        }
        String username = access.currentUsername();
        List<Map<String, Object>> results = new ArrayList<>();
        int accepted = 0;
        for (MultipartFile photo : photos) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("file", photo.getOriginalFilename());
            try {
                FaceSampleService.AddOutcome outcome =
                        sampleService.addUploadedSample(employeeId, photo.getBytes(), force, username);
                r.put("accepted", outcome.accepted());
                r.put("code", outcome.code());
                r.put("message", outcome.message());
                r.put("sample", outcome.sample());
                if (outcome.accepted()) {
                    accepted++;
                }
            } catch (Exception e) {
                log.warn("Face sample upload failed for employee {}: {}", employeeId, e.getMessage());
                r.put("accepted", false);
                r.put("code", "ERROR");
                r.put("message", e.getMessage());
            }
            results.add(r);
        }
        String message = accepted + " of " + photos.size() + " photo(s) added";
        return ResponseEntity.ok(accepted > 0
                ? ApiResponse.success(message, results)
                : ApiResponse.<List<Map<String, Object>>>builder()
                        .success(false).message(message).data(results).timestamp(LocalDateTime.now()).build());
    }

    @DeleteMapping("/{sampleId}")
    @RequiresPermission(module = EMPLOYEES, action = MODIFY)
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long employeeId, @PathVariable Long sampleId) {
        access.requireEmployee(EMPLOYEES, MODIFY, employeeId);
        try {
            sampleService.deleteSample(employeeId, sampleId);
            return ResponseEntity.ok(ApiResponse.success("Photo deleted", null));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }
}
