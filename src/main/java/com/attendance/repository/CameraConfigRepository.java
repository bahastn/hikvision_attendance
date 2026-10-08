package com.attendance.repository;

import com.attendance.entity.CameraConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CameraConfigRepository extends JpaRepository<CameraConfig, Long> {

    List<CameraConfig> findByIsActiveTrue();

    Optional<CameraConfig> findByCameraName(String cameraName);

    List<CameraConfig> findByIsConnectedTrue();
}
