package com.attendance.service;

import com.attendance.entity.CameraConfig;
import com.attendance.entity.Employee;
import com.attendance.entity.EmployeeCameraTarget;
import com.attendance.repository.CameraConfigRepository;
import com.attendance.repository.EmployeeCameraTargetRepository;
import com.attendance.repository.EmployeeRepository;
import com.attendance.service.insightface.FaceEmbeddingEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Two-way comparison between the app and a camera's face library, and the repair of the
 * differences. The existing library sync only goes camera → app (import / link); this adds the
 * app → camera direction and keeps the per-camera links honest:
 *
 * <ul>
 *   <li><b>Link</b>: people on the camera whose ID matches an active employee get their per-camera
 *       PID recorded (so matches resolve by PID).</li>
 *   <li><b>Re-send</b>: employees the app believes are on the camera but who are missing there
 *       (deleted on the camera, library reset, camera replaced) are pushed again. When there is no
 *       photo to push, the stale link is cleared so the app shows "not enrolled".</li>
 *   <li><b>Remove</b>: people on the camera who are deactivated in the app are removed.</li>
 *   <li><b>Send new</b> (optional, off by default): active employees with a photo that were never
 *       sent to this camera.</li>
 *   <li><b>Photos</b>: linked employees without a photo in the app get the camera's photo, so they
 *       can be re-sent later and used by InsightFace.</li>
 * </ul>
 * Names are never overwritten here; differences are only reported.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaceLibraryReconcileService {

    private final CameraConfigRepository cameraRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeCameraTargetRepository targetRepository;
    private final HikvisionIsapiClient isapiClient;
    private final FaceEnrollmentService enrollmentService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${attendance.camera.face-lib-id:1}")
    private String faceLibId;

    @Value("${attendance.camera.face-lib-max-results:5000}")
    private int maxPeople;

    @Value("${attendance.face-storage-dir:faces}")
    private String faceStorageDir;

    @Value("${attendance.camera.library-reconcile.enabled:true}")
    private boolean scheduledEnabled;

    @Value("${attendance.camera.library-reconcile.send-new:false}")
    private boolean scheduledSendNew;

    /** At most this many photos are downloaded from a camera per run. */
    @Value("${attendance.camera.library-reconcile.max-photo-downloads:200}")
    private int maxPhotoDownloads;

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    /** Status of one person on the camera. */
    public enum CameraStatus { LINKED, NAME_DIFFERS, CAMERA_ONLY, NO_CERTIFICATE, DEACTIVATED }

    /** Status of one employee missing on the camera. */
    public enum AppStatus { MISSING_ON_CAMERA, NOT_SENT }

    public record CameraPerson(String pid, String name, String certificateNumber, CameraStatus status,
                               Long employeeDbId, String employeeId, String employeeName) {
    }

    public record AppEmployee(Long employeeDbId, String employeeId, String name, AppStatus status,
                              String expectedPid, boolean hasPhoto) {
    }

    public record Report(Long cameraId, String cameraName, LocalDateTime generatedAt, boolean reachable,
                         int onCamera, int linked, int nameDiffers, int cameraOnly, int noCertificate,
                         int deactivatedOnCamera, int missingOnCamera, int notSent,
                         List<CameraPerson> cameraPeople, List<AppEmployee> appEmployees) {
    }

    public record Options(boolean link, boolean resendMissing, boolean removeDeactivated, boolean sendNew,
                          boolean fetchPhotos) {
        public static Options defaults() {
            return new Options(true, true, true, false, true);
        }
    }

    public record Result(Long cameraId, String cameraName, int linked, int resent, int removed, int sent,
                         int cleared, int photos, int failed, List<String> messages) {
    }

    // ------------------------------------------------------------------
    // Compare
    // ------------------------------------------------------------------

    /** Compare one camera with the app without changing anything. */
    public Report report(Long cameraId) {
        CameraConfig camera = activeCamera(cameraId);
        Optional<List<HikvisionIsapiClient.FaceLibPerson>> people = isapiClient.fetchFaceLibrary(camera, faceLibId, maxPeople);
        if (people.isEmpty()) {
            return new Report(cameraId, camera.getCameraName(), LocalDateTime.now(), false,
                    0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
        }
        return compare(camera, people.get());
    }

    private Report compare(CameraConfig camera, List<HikvisionIsapiClient.FaceLibPerson> people) {
        List<Employee> employees = employeeRepository.findAll();
        Map<String, Employee> byEmployeeId = employees.stream()
                .filter(e -> e.getEmployeeId() != null)
                .collect(Collectors.toMap(Employee::getEmployeeId, Function.identity(), (a, b) -> a));
        Map<Long, Employee> byDbId = employees.stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity(), (a, b) -> a));
        // Per-camera links: PID -> employee db id (target.getEmployee() is a lazy proxy; getId() is safe).
        List<EmployeeCameraTarget> targets = targetRepository.findByCameraId(camera.getId());
        Map<String, Long> employeeByPid = new HashMap<>();
        Map<Long, String> pidByEmployee = new HashMap<>();
        for (EmployeeCameraTarget t : targets) {
            if (t.getPid() != null && t.getEmployee() != null) {
                employeeByPid.put(t.getPid(), t.getEmployee().getId());
                pidByEmployee.put(t.getEmployee().getId(), t.getPid());
            }
        }

        Set<String> pidsOnCamera = new HashSet<>();
        Set<String> certsOnCamera = new HashSet<>();
        List<CameraPerson> cameraPeople = new ArrayList<>();
        int linked = 0, nameDiffers = 0, cameraOnly = 0, noCert = 0, deactivated = 0;

        for (HikvisionIsapiClient.FaceLibPerson p : people) {
            pidsOnCamera.add(p.pid);
            String cert = p.certificateNumber != null && !p.certificateNumber.isBlank() ? p.certificateNumber.trim() : null;
            if (cert != null) {
                certsOnCamera.add(cert);
            }
            Employee e = cert != null ? byEmployeeId.get(cert) : null;
            if (e == null && employeeByPid.containsKey(p.pid)) {
                e = byDbId.get(employeeByPid.get(p.pid));
            }
            CameraStatus status;
            if (e == null) {
                status = cert == null ? CameraStatus.NO_CERTIFICATE : CameraStatus.CAMERA_ONLY;
                if (cert == null) noCert++; else cameraOnly++;
            } else if (!Boolean.TRUE.equals(e.getIsActive())) {
                status = CameraStatus.DEACTIVATED;
                deactivated++;
            } else if (p.name != null && !p.name.isBlank() && !p.name.trim().equalsIgnoreCase(nz(e.getFullName()))
                    && !p.name.trim().equalsIgnoreCase(nz(e.getFullNameKurdish()))) {
                status = CameraStatus.NAME_DIFFERS;
                nameDiffers++;
                linked++;
            } else {
                status = CameraStatus.LINKED;
                linked++;
            }
            cameraPeople.add(new CameraPerson(p.pid, p.name, cert, status,
                    e != null ? e.getId() : null, e != null ? e.getEmployeeId() : null, e != null ? e.getFullName() : null));
        }

        List<AppEmployee> appEmployees = new ArrayList<>();
        int missing = 0, notSent = 0;
        for (Employee e : employees) {
            if (!Boolean.TRUE.equals(e.getIsActive())) {
                continue;
            }
            boolean onCamera = (e.getEmployeeId() != null && certsOnCamera.contains(e.getEmployeeId()))
                    || (pidByEmployee.containsKey(e.getId()) && pidsOnCamera.contains(pidByEmployee.get(e.getId())));
            if (onCamera) {
                continue;
            }
            boolean hasPhoto = storedPhoto(e).isPresent();
            if (pidByEmployee.containsKey(e.getId())) {
                appEmployees.add(new AppEmployee(e.getId(), e.getEmployeeId(), e.getFullName(),
                        AppStatus.MISSING_ON_CAMERA, pidByEmployee.get(e.getId()), hasPhoto));
                missing++;
            } else if (hasPhoto) {
                appEmployees.add(new AppEmployee(e.getId(), e.getEmployeeId(), e.getFullName(),
                        AppStatus.NOT_SENT, null, true));
                notSent++;
            }
        }

        return new Report(camera.getId(), camera.getCameraName(), LocalDateTime.now(), true, people.size(),
                linked, nameDiffers, cameraOnly, noCert, deactivated, missing, notSent, cameraPeople, appEmployees);
    }

    // ------------------------------------------------------------------
    // Repair
    // ------------------------------------------------------------------

    /**
     * Fix the differences on one camera. Not one big transaction on purpose: each change (camera
     * call + its database update) stands on its own, so one failure does not undo the rest.
     */
    public Result reconcile(Long cameraId, Options options) {
        CameraConfig camera = activeCamera(cameraId);
        Optional<List<HikvisionIsapiClient.FaceLibPerson>> fetched = isapiClient.fetchFaceLibrary(camera, faceLibId, maxPeople);
        if (fetched.isEmpty()) {
            // Never act on an unreachable camera: an "empty" library would look like everyone is missing.
            return new Result(cameraId, camera.getCameraName(), 0, 0, 0, 0, 0, 0, 0,
                    List.of("Camera face library could not be read — nothing changed"));
        }
        List<HikvisionIsapiClient.FaceLibPerson> people = fetched.get();
        Report report = compare(camera, people);
        Map<String, HikvisionIsapiClient.FaceLibPerson> personByPid = people.stream()
                .collect(Collectors.toMap(p -> p.pid, Function.identity(), (a, b) -> a));

        int linked = 0, resent = 0, removed = 0, sent = 0, cleared = 0, photos = 0, failed = 0;
        List<String> messages = new ArrayList<>();

        for (CameraPerson p : report.cameraPeople()) {
            try {
                if ((p.status() == CameraStatus.LINKED || p.status() == CameraStatus.NAME_DIFFERS) && p.employeeDbId() != null) {
                    if (options.link() && linkTarget(camera, p.employeeDbId(), p.pid())) {
                        linked++;
                    }
                    if (options.fetchPhotos() && photos < maxPhotoDownloads && fetchPhotoIfMissing(camera, p.employeeDbId(), p.pid())) {
                        photos++;
                        messages.add("Photo of " + p.employeeName() + " copied from the camera");
                    }
                } else if (p.status() == CameraStatus.DEACTIVATED && options.removeDeactivated()) {
                    HikvisionIsapiClient.FaceLibResult del = isapiClient.deleteFaceFromLibrary(camera, faceLibId, p.pid());
                    if (del.success) {
                        removed++;
                        targetRepository.findByEmployeeIdAndCameraId(p.employeeDbId(), camera.getId())
                                .ifPresent(targetRepository::delete);
                        messages.add("Removed " + p.name() + " (deactivated in the app)");
                    } else {
                        failed++;
                        messages.add("Could not remove " + p.name() + ": " + del.message);
                    }
                }
            } catch (Exception e) {
                failed++;
                messages.add("Error for " + p.name() + ": " + e.getMessage());
                log.warn("Reconcile of PID {} on {} failed: {}", p.pid(), camera.getCameraName(), e.getMessage());
            }
        }

        for (AppEmployee a : report.appEmployees()) {
            boolean missing = a.status() == AppStatus.MISSING_ON_CAMERA;
            if ((missing && !options.resendMissing()) || (!missing && !options.sendNew())) {
                continue;
            }
            if (!a.hasPhoto()) {
                if (missing) {
                    // Nothing to send: drop the stale link so the app shows "not enrolled" here.
                    targetRepository.findByEmployeeIdAndCameraId(a.employeeDbId(), camera.getId())
                            .ifPresent(targetRepository::delete);
                    cleared++;
                    messages.add(a.name() + " is not on the camera and has no photo in the app — marked not enrolled");
                }
                continue;
            }
            try {
                ensureEnrollmentPhotoFile(a.employeeDbId());
                enrollmentService.enrollOnCamera(a.employeeDbId(), camera.getId());
                if (missing) resent++; else sent++;
                messages.add((missing ? "Re-sent " : "Sent ") + a.name() + " (" + a.employeeId() + ")");
            } catch (Exception e) {
                failed++;
                messages.add("Could not send " + a.name() + ": " + e.getMessage());
                log.warn("Sending {} to {} failed: {}", a.employeeId(), camera.getCameraName(), e.getMessage());
            }
        }

        camera.setLastLibrarySync(LocalDateTime.now());
        cameraRepository.save(camera);
        log.info("Face library reconcile on {}: {} linked, {} re-sent, {} sent, {} removed, {} cleared, {} photos, {} failed",
                camera.getCameraName(), linked, resent, sent, removed, cleared, photos, failed);
        if (personByPid.isEmpty() && report.appEmployees().isEmpty()) {
            messages.add("Camera library is empty and no employee is expected on it");
        }
        return new Result(camera.getId(), camera.getCameraName(), linked, resent, removed, sent, cleared, photos,
                failed, messages);
    }

    /** Nightly: reconcile every active camera with the default options. */
    @Scheduled(cron = "${attendance.camera.library-reconcile.cron:0 30 2 * * ?}")
    public void scheduledReconcile() {
        if (!scheduledEnabled) {
            return;
        }
        Options options = new Options(true, true, true, scheduledSendNew, true);
        for (CameraConfig camera : cameraRepository.findByIsActiveTrue()) {
            try {
                reconcile(camera.getId(), options);
            } catch (Exception e) {
                log.warn("Scheduled face library reconcile failed for {}: {}", camera.getCameraName(), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Record (or correct) the employee's PID on this camera. Returns true when something changed. */
    private boolean linkTarget(CameraConfig camera, Long employeeDbId, String pid) {
        Optional<EmployeeCameraTarget> existing = targetRepository.findByEmployeeIdAndCameraId(employeeDbId, camera.getId());
        if (existing.isPresent() && pid.equals(existing.get().getPid())) {
            return false;
        }
        Employee employee = employeeRepository.findById(employeeDbId).orElse(null);
        if (employee == null) {
            return false;
        }
        EmployeeCameraTarget target = existing.orElseGet(() -> EmployeeCameraTarget.builder()
                .employee(employee).camera(camera).build());
        target.setPid(pid);
        target.setEnrolledAt(LocalDateTime.now());
        targetRepository.save(target);
        if (employee.getFaceTemplateId() == null || employee.getFaceTemplateId().isBlank()) {
            employee.setFaceTemplateId(pid);
            employeeRepository.save(employee);
        }
        return true;
    }

    /**
     * When the employee has no photo in the app, download the camera's library photo and store it
     * as their enrollment photo (also used by InsightFace). Returns true when a photo was stored.
     */
    private boolean fetchPhotoIfMissing(CameraConfig camera, Long employeeDbId, String pid) throws Exception {
        Employee employee = employeeRepository.findById(employeeDbId).orElse(null);
        if (employee == null || storedPhoto(employee).isPresent()) {
            return false;
        }
        Optional<byte[]> photo = isapiClient.downloadFacePicture(camera, faceLibId, pid);
        if (photo.isEmpty() || photo.get().length == 0) {
            return false;
        }
        Path dir = Paths.get(faceStorageDir, "enrolled");
        Files.createDirectories(dir);
        Files.write(dir.resolve("employee_" + employeeDbId + ".jpg"), photo.get());
        employee.setFaceImageUrl("/faces/enrolled/employee_" + employeeDbId + ".jpg");
        employeeRepository.save(employee);
        // Build the InsightFace sample from the new photo (after commit, async; no-op when off).
        eventPublisher.publishEvent(new FaceEmbeddingEvents.FaceEnrolled(employeeDbId));
        return true;
    }

    /**
     * The employee's photo file: the standard enrollment file, or the file their photo URL points
     * to (e.g. a photo imported from a camera under another name).
     */
    private Optional<Path> storedPhoto(Employee e) {
        Path standard = Paths.get(faceStorageDir, "enrolled", "employee_" + e.getId() + ".jpg");
        if (Files.isRegularFile(standard)) {
            return Optional.of(standard);
        }
        String url = e.getFaceImageUrl();
        if (url != null && url.startsWith("/faces/")) {
            Path base = Paths.get(faceStorageDir).toAbsolutePath().normalize();
            Path file = base.resolve(url.substring("/faces/".length())).normalize();
            if (file.startsWith(base) && Files.isRegularFile(file)) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    /**
     * Enrollment reads {@code enrolled/employee_{id}.jpg}; copy a photo stored under another name
     * there first so it can be sent.
     */
    private void ensureEnrollmentPhotoFile(Long employeeDbId) throws Exception {
        Employee e = employeeRepository.findById(employeeDbId).orElse(null);
        if (e == null) {
            return;
        }
        Path standard = Paths.get(faceStorageDir, "enrolled", "employee_" + e.getId() + ".jpg");
        if (Files.isRegularFile(standard)) {
            return;
        }
        Optional<Path> other = storedPhoto(e);
        if (other.isPresent()) {
            Files.createDirectories(standard.getParent());
            Files.copy(other.get(), standard);
        }
    }

    private CameraConfig activeCamera(Long cameraId) {
        return cameraRepository.findById(cameraId)
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .orElseThrow(() -> new RuntimeException("Camera not found or not active"));
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
