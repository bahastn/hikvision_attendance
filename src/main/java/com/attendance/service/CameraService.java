package com.attendance.service;

import com.attendance.dto.CameraDTO;
import com.attendance.entity.CameraConfig;
import com.attendance.repository.CameraConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CameraService {

    private final CameraConfigRepository cameraRepository;
    private final HikvisionIsapiClient isapiClient;

    @Transactional(readOnly = true)
    public List<CameraDTO> getAllCameras() {
        return cameraRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CameraDTO> getActiveCameras() {
        return cameraRepository.findByIsActiveTrue().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public CameraDTO addCamera(CameraDTO dto) {
        CameraConfig camera = CameraConfig.builder()
                .cameraName(dto.getCameraName())
                .ipAddress(dto.getIpAddress())
                .port(dto.getPort())
                .username(dto.getUsername())
                .password(dto.getPassword())
                .protocol(dto.getProtocol())
                .sdkPort(dto.getSdkPort())
                .websocketPort(dto.getWebsocketPort())
                .rtspPort(dto.getRtspPort())
                .location(dto.getLocation())
                .isActive(true)
                .isConnected(false)
                .build();
        return toDTO(cameraRepository.save(camera));
    }

    @Transactional
    public CameraDTO updateCamera(Long id, CameraDTO dto) {
        CameraConfig camera = cameraRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Camera not found"));

        boolean endpointChanged =
                !java.util.Objects.equals(camera.getIpAddress(), dto.getIpAddress())
                || !java.util.Objects.equals(camera.getPort(), dto.getPort())
                || !java.util.Objects.equals(camera.getProtocol(), dto.getProtocol())
                || !java.util.Objects.equals(camera.getUsername(), dto.getUsername());
        boolean passwordSupplied = dto.getPassword() != null && !dto.getPassword().isBlank();
        if (endpointChanged && !passwordSupplied) {
            // Never reuse the stored password against a new address/port/host: that would
            // leak the camera's admin credentials to whatever host the new address points to.
            throw new IllegalArgumentException(
                    "Re-enter the camera password when changing its address, port, protocol or username");
        }

        camera.setCameraName(dto.getCameraName());
        camera.setIpAddress(dto.getIpAddress());
        camera.setPort(dto.getPort());
        camera.setUsername(dto.getUsername());
        if (passwordSupplied) {
            camera.setPassword(dto.getPassword());
        }
        camera.setProtocol(dto.getProtocol());
        camera.setSdkPort(dto.getSdkPort());
        camera.setWebsocketPort(dto.getWebsocketPort());
        camera.setRtspPort(dto.getRtspPort());
        camera.setLocation(dto.getLocation());
        camera.setIsActive(dto.getIsActive());
        return toDTO(cameraRepository.save(camera));
    }

    @Transactional
    public void deleteCamera(Long id) {
        CameraConfig camera = cameraRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Camera not found"));
        camera.setIsActive(false);
        cameraRepository.save(camera);
    }

    /**
     * Test connection with explicit credentials (for diagnostic purposes).
     * Does NOT save anything to the database.
     */
    public boolean testConnectionWithCredentials(String ip, Integer port, String protocol,
                                                  String username, String password) {
        return isapiClient.testConnectionWithCredentials(ip, port, protocol, username, password);
    }

    /** Latest JPEG frame from a camera channel (101 = camera 1 main stream). */
    @Transactional(readOnly = true)
    public Optional<byte[]> getSnapshot(Long id, int channel) {
        return cameraRepository.findById(id)
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .flatMap(c -> isapiClient.snapshot(c, channel));
    }

    @Transactional
    public boolean testConnection(Long id) {
        CameraConfig camera = cameraRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Camera not found"));

        // Real ISAPI connection test (digest auth against the camera)
        boolean connected = isapiClient.testIsapiConnection(camera);
        camera.setIsConnected(connected);
        if (connected) {
            camera.setLastSync(LocalDateTime.now());
        }
        cameraRepository.save(camera);
        return connected;
    }

    private CameraDTO toDTO(CameraConfig camera) {
        return CameraDTO.builder()
                .id(camera.getId())
                .cameraName(camera.getCameraName())
                .ipAddress(camera.getIpAddress())
                .port(camera.getPort())
                .username(camera.getUsername())
                .protocol(camera.getProtocol())
                .sdkPort(camera.getSdkPort())
                .websocketPort(camera.getWebsocketPort())
                .rtspPort(camera.getRtspPort())
                .isActive(camera.getIsActive())
                .isConnected(camera.getIsConnected())
                .location(camera.getLocation())
                .lastSync(camera.getLastSync() != null ? 
                    camera.getLastSync().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : null)
                .build();
    }
}
