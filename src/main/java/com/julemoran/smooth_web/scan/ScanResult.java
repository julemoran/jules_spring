package com.julemoran.smooth_web.scan;

import com.julemoran.smooth_web.location.LocationDto; // Assuming ScannedFile will be simplified or use a DTO form

import java.util.List;
import java.time.LocalDateTime;

/**
 * Represents the outcome of a scan task.
 *
 * @param finalStatus The terminal status of the scan (COMPLETED, FAILED, ABORTED).
 * @param discoveredFiles A list of DTOs for files found (relevant if status is COMPLETED).
 *                          Using placeholder "Object" for now, should be a ScannedFile DTO.
 * @param errorMessage An error message if the scan failed.
 * @param startTime The time the scan processing started.
 * @param endTime The time the scan processing ended.
 */
public record ScanResult(
    ScanStatus finalStatus,
    List<ScannedFile> discoveredFiles, // Will hold ScannedFile entities ready for persistence
    String errorMessage,
    LocalDateTime startTime,
    LocalDateTime endTime
) {
    // Convenience constructor for success
    public static ScanResult completed(List<ScannedFile> files, LocalDateTime startTime, LocalDateTime endTime) {
        return new ScanResult(ScanStatus.COMPLETED, files, null, startTime, endTime);
    }

    // Convenience constructor for failure
    public static ScanResult failed(String message, LocalDateTime startTime, LocalDateTime endTime) {
        return new ScanResult(ScanStatus.FAILED, List.of(), message, startTime, endTime);
    }

    // Convenience constructor for abort
    public static ScanResult aborted(LocalDateTime startTime, LocalDateTime endTime) {
        return new ScanResult(ScanStatus.ABORTED, List.of(), "Scan was aborted.", startTime, endTime);
    }
}
