package com.attendance.entity;

/**
 * Tracks the synchronization status of an employee with camera face libraries.
 */
public enum SyncStatus {
    PENDING,    // Not yet synced to any camera
    SYNCED,     // Successfully synced to all target cameras
    PARTIAL,    // Synced to some but not all cameras
    CONFLICT,   // Data mismatch between camera and database
    ERROR       // Sync failed
}
