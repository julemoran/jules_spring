package com.julemoran.smooth_web.scan;

import com.julemoran.smooth_web.location.Location;
import com.julemoran.smooth_web.location.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ScanStateManager {
    private static final Logger logger = LoggerFactory.getLogger(ScanStateManager.class);

    private final ConcurrentHashMap<Long, ScanState> inMemoryScanStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Lock> locationLocks = new ConcurrentHashMap<>();

    private final LocationRepository locationRepository;
    private final LocationScanStatusRepository locationScanStatusRepository;
    private final ScannedFileRepository scannedFileRepository;

    public ScanStateManager(LocationRepository locationRepository,
                            LocationScanStatusRepository locationScanStatusRepository,
                            ScannedFileRepository scannedFileRepository) {
        this.locationRepository = locationRepository;
        this.locationScanStatusRepository = locationScanStatusRepository;
        this.scannedFileRepository = scannedFileRepository;
    }

    private Lock getLockForLocation(Long locationId) {
        return locationLocks.computeIfAbsent(locationId, k -> new ReentrantLock());
    }

    public boolean canStartScan(Long locationId) {
        Lock lock = getLockForLocation(locationId);
        lock.lock();
        try {
            ScanState currentState = inMemoryScanStates.get(locationId);
            if (currentState != null && (currentState.status() == ScanStatus.RUNNING || currentState.status() == ScanStatus.ABORTING)) {
                logger.warn("Scan for location ID {} is already {} or {}. Cannot start new scan.",
                        locationId, currentState.status(), ScanStatus.ABORTING);
                return false;
            }
            // Transition to RUNNING state in memory
            inMemoryScanStates.put(locationId, new ScanState(ScanStatus.RUNNING));
            logger.info("In-memory state for location ID {} set to RUNNING.", locationId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void requestAbort(Long locationId) {
        Lock lock = getLockForLocation(locationId);
        lock.lock();
        try {
            ScanState currentState = inMemoryScanStates.get(locationId);
            if (currentState != null && currentState.status() == ScanStatus.RUNNING) {
                currentState.abortRequested().set(true);
                inMemoryScanStates.put(locationId, new ScanState(ScanStatus.ABORTING, currentState.abortRequested()));
                logger.info("Abort requested for location ID {}. In-memory state set to ABORTING.", locationId);
                // Persist ABORTING state to DB immediately
                // This needs to be transactional
                updatePersistedStatus(locationId, ScanStatus.ABORTING, null, LocalDateTime.now());
            } else if (currentState != null) {
                logger.warn("Scan for location ID {} is not in RUNNING state (current: {}). Cannot abort.", locationId, currentState.status());
            } else {
                logger.warn("No active scan found in memory for location ID {}. Cannot abort.", locationId);
                // Optional: Check DB and update if it's RUNNING (consistency check)
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean isAbortRequested(Long locationId) {
        ScanState currentState = inMemoryScanStates.get(locationId);
        return currentState != null && currentState.abortRequested().get();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordScanInProgress(Long locationId, LocalDateTime startTime) {
        Location location = locationRepository.findById(locationId)
                .orElseThrow(() -> new IllegalArgumentException("Location not found: " + locationId));
        LocationScanStatus status = locationScanStatusRepository.findByLocation(location)
                .orElseGet(() -> new LocationScanStatus(location, ScanStatus.IDLE));

        status.setStatus(ScanStatus.RUNNING);
        status.setLastScanStartTime(startTime);
        status.setLastScanEndTime(null);
        locationScanStatusRepository.saveAndFlush(status);
        logger.info("Persisted scan status for location ID {} to RUNNING.", locationId);
    }


    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordScanCompleted(Long locationId, LocalDateTime startTime, LocalDateTime endTime, List<ScannedFile> discoveredFiles) {
        Lock lock = getLockForLocation(locationId);
        lock.lock();
        try {
            // Clear previous files for this location before saving new ones
            logger.info("Clearing previous ScannedFile records for location ID: {}", locationId);
            scannedFileRepository.deleteByLocationId(locationId);
            logger.info("Successfully cleared previous ScannedFile records for location ID: {}", locationId);

            updatePersistedStatus(locationId, ScanStatus.COMPLETED, startTime, endTime);
            if (discoveredFiles != null && !discoveredFiles.isEmpty()) {
                // Ensure Location object is set for ScannedFile entities if it's not already
                // This might be needed if 'location' field in ScannedFile is transient or not set by FileScannerService
                Location location = locationRepository.findById(locationId)
                                    .orElseThrow(() -> new IllegalStateException("Location not found when saving scanned files: " + locationId));
                discoveredFiles.forEach(file -> file.setLocation(location));

                scannedFileRepository.saveAll(discoveredFiles);
                logger.info("Saved {} scanned files for location ID {}.", discoveredFiles.size(), locationId);
            }
            inMemoryScanStates.put(locationId, new ScanState(ScanStatus.COMPLETED));
        } finally {
            lock.unlock();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordScanFailed(Long locationId, LocalDateTime startTime, LocalDateTime endTime) {
        Lock lock = getLockForLocation(locationId);
        lock.lock();
        try {
            updatePersistedStatus(locationId, ScanStatus.FAILED, startTime, endTime);
            inMemoryScanStates.put(locationId, new ScanState(ScanStatus.FAILED));
        } finally {
            lock.unlock();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordScanAborted(Long locationId, LocalDateTime startTime, LocalDateTime endTime) {
        Lock lock = getLockForLocation(locationId);
        lock.lock();
        try {
            updatePersistedStatus(locationId, ScanStatus.ABORTED, startTime, endTime);
            // Clear any potentially half-processed files for this scan attempt if needed
            // For now, assuming FileScannerService handles not persisting on abort.
            // If files were already persisted by FileScannerService before abort was caught,
            // they might need cleanup here. Current FileScannerService saves at the end.
            inMemoryScanStates.put(locationId, new ScanState(ScanStatus.ABORTED));
        } finally {
            lock.unlock();
        }
    }

    @Transactional // Uses default propagation (REQUIRED)
    public LocationScanStatus getPersistedScanStatus(Long locationId) {
        Location location = locationRepository.findById(locationId)
                .orElseThrow(() -> new IllegalArgumentException("Location not found: " + locationId));
        return locationScanStatusRepository.findByLocation(location)
                .orElseGet(() -> {
                    logger.info("No scan status found for location ID: {}. Creating and saving a default IDLE status.", locationId);
                    LocationScanStatus newStatus = new LocationScanStatus(location, ScanStatus.IDLE);
                    return locationScanStatusRepository.save(newStatus);
                });
    }

    // Helper to update DB status consistently
    private void updatePersistedStatus(Long locationId, ScanStatus newDbStatus, LocalDateTime startTime, LocalDateTime endTime) {
        Location location = locationRepository.findById(locationId)
                .orElseThrow(() -> new IllegalArgumentException("Location not found for status update: " + locationId));
        LocationScanStatus status = locationScanStatusRepository.findByLocation(location)
                .orElseGet(() -> new LocationScanStatus(location, ScanStatus.IDLE)); // Should exist if scan was started

        status.setStatus(newDbStatus);
        if (startTime != null) { // Only update start time if provided (e.g. not for abort if it was already running)
            status.setLastScanStartTime(startTime);
        }
        status.setLastScanEndTime(endTime);
        locationScanStatusRepository.saveAndFlush(status);
        logger.info("Persisted scan status for location ID {} to {}.", locationId, newDbStatus);
    }

     // To be called by FileScannerService to indicate the scan process has actually started working
    public void confirmScanActuallyStarted(Long locationId) {
        Lock lock = getLockForLocation(locationId);
        lock.lock();
        try {
            ScanState currentState = inMemoryScanStates.get(locationId);
            if (currentState != null && currentState.status() == ScanStatus.RUNNING) {
                 // Already RUNNING, this is fine. This method is a confirmation.
                logger.info("Scan for location {} confirmed as RUNNING in memory.", locationId);
            } else {
                 // This case should ideally not be hit if canStartScan was called and returned true.
                logger.warn("confirmScanActuallyStarted called for location {} but in-memory state was not RUNNING (was: {}). Setting to RUNNING.",
                        locationId, currentState != null ? currentState.status() : "null");
                inMemoryScanStates.put(locationId, new ScanState(ScanStatus.RUNNING));
            }
        } finally {
            lock.unlock();
        }
    }
}
