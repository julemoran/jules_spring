package com.julemoran.smooth_web.scan;

public enum ScanStatus {
    IDLE,       // Not currently scanning, no scan has been run or last scan finished
    RUNNING,    // Scan is actively in progress
    ABORTING,   // Request to abort has been made, scanner is shutting down
    ABORTED,    // Scan was aborted before completion
    COMPLETED,  // Scan finished successfully
    FAILED      // Scan failed due to an error
}
