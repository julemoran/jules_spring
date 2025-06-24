package com.julemoran.smooth_web.scan;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents the in-memory state of a scan for a specific location.
 *
 * @param status The current status of the scan.
 * @param abortRequested Flag to indicate if an abort has been requested.
 */
public record ScanState(ScanStatus status, AtomicBoolean abortRequested) {
    public ScanState(ScanStatus status) {
        this(status, new AtomicBoolean(false));
    }
}
