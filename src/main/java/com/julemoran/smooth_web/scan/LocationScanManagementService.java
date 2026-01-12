package com.julemoran.smooth_web.scan;

import com.julemoran.smooth_web.location.Location;
import com.julemoran.smooth_web.location.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class LocationScanManagementService {

    private static final Logger logger = LoggerFactory.getLogger(LocationScanManagementService.class);

    private final LocationRepository locationRepository;
    private final FileScannerService fileScannerService;
    private final LocationScanStatusRepository locationScanStatusRepository;

    public LocationScanManagementService(LocationRepository locationRepository,
                                         FileScannerService fileScannerService,
                                         LocationScanStatusRepository locationScanStatusRepository) {
        this.locationRepository = locationRepository;
        this.fileScannerService = fileScannerService;
        this.locationScanStatusRepository = locationScanStatusRepository;
    }

    /**
     * Initiates a scan for the given location ID.
     * This method runs asynchronously due to FileScannerService's async nature for hashing
     * and the overall potentially long-running scan process.
     * The method itself returns quickly after submitting the scan task.
     *
     * @param locationId The ID of the location to scan.
     * @param calculateHash Whether to calculate SHA256 hashes for files.
     */
    @Transactional // Manages the transaction for fetching location and initial status check/update
    public void startScan(Long locationId, boolean calculateHash) {
        Location location = locationRepository.findById(locationId)
                .orElseThrow(() -> {
                    logger.warn("Attempted to start scan for non-existent location ID: {}", locationId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Location not found with ID: " + locationId);
                });

        logger.info("Request received to scan location: {} (ID: {}), calculateHash: {}", location.getName(), locationId, calculateHash);

        LocationScanStatus status = locationScanStatusRepository.findByLocation(location)
                .orElseGet(() -> {
                    LocationScanStatus newStatus = new LocationScanStatus(location, ScanStatus.IDLE);
                    // Persist immediately if it's new, so FileScannerService can update it
                    return locationScanStatusRepository.save(newStatus);
                });


        if (status.getStatus() == ScanStatus.RUNNING || status.getStatus() == ScanStatus.ABORTING) {
            logger.warn("Scan for location '{}' (ID: {}) is already {} or {}. New scan request ignored.",
                    location.getName(), locationId, status.getStatus(), ScanStatus.ABORTING);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Scan for location " + location.getName() + " is already " + status.getStatus());
        }

        // The actual scanning (potentially long-running) is then delegated.
        // FileScannerService.scanLocation handles its own transaction for the scan itself.
        // We are not directly calling an @Async method here, but FileScannerService.scanLocation
        // uses @Async internally for hashing. The scan itself is blocking within its own thread.
        // To make this truly non-blocking for the caller of startScan, FileScannerService.scanLocation
        // would need to be @Async or called via an ExecutorService.
        // For now, this method will block until scanLocation completes.
        // Let's adjust this to make startScan itself asynchronous.
        // This requires FileScannerService.scanLocation to be public and ideally on a different bean
        // or configured for self-invocation if it were @Async itself.
        // Since FileScannerService.scanLocation is already public and on a different bean,
        // we can make this method @Async or call it via an executor.
        // For simplicity, we'll rely on the fact that the *controller* calling this might be async,
        // or that a separate thread calls this.
        // The actual file iteration and hashing inside FileScannerService is the long part.

        // Resetting status to IDLE here if it was FAILED or ABORTED to allow re-scan.
        // If it was COMPLETED, it will also be reset to allow re-scan.
        status.setStatus(ScanStatus.IDLE); // Set to IDLE before scanLocation attempts to set to RUNNING
        status.setLastScanStartTime(null);
        status.setLastScanEndTime(null);
        locationScanStatusRepository.save(status);

        // Delegate to FileScannerService. This call will manage its own transaction.
        // Note: if scanLocation were @Async, this would be a fire-and-forget call.
        // As it is now, it's a synchronous call within this transaction.
        // The @Async for hashing is internal to scanLocation.
        try {
             // This call is synchronous at this level. The async part is the hashing within scanLocation.
            fileScannerService.scanLocation(location, calculateHash);
        } catch (Exception e) {
            logger.error("Exception propagated from fileScannerService.scanLocation for location ID {}: {}", locationId, e.getMessage(), e);
            // Ensure status is FAILED if an unexpected error escapes scanLocation
            LocationScanStatus errorStatus = locationScanStatusRepository.findByLocationId(locationId)
                .orElseThrow(() -> new IllegalStateException("Scan status not found after error for location " + locationId));
            if (errorStatus.getStatus() != ScanStatus.ABORTED) { // Don't override ABORTED
                 errorStatus.setStatus(ScanStatus.FAILED);
                 errorStatus.setLastScanEndTime(LocalDateTime.now());
                 locationScanStatusRepository.save(errorStatus);
            }
            // Re-throw or handle as per API contract
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Scan failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void abortScan(Long locationId) {
        Location location = locationRepository.findById(locationId)
                .orElseThrow(() -> {
                    logger.warn("Attempted to abort scan for non-existent location ID: {}", locationId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Location not found with ID: " + locationId);
                });

        logger.info("Request received to abort scan for location: {} (ID: {})", location.getName(), locationId);
        fileScannerService.requestAbortScan(locationId);
    }

    @Transactional // Changed to read-write to allow creation of status if not present
    public LocationScanStatus getScanStatus(Long locationId) { // Return type changed to non-Optional
        Location location = locationRepository.findById(locationId)
                .orElseThrow(() -> {
                    logger.warn("Attempted to get scan status for non-existent location ID: {}", locationId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Location not found with ID: " + locationId);
                });

        return locationScanStatusRepository.findByLocation(location)
                .orElseGet(() -> {
                    logger.info("No scan status found for location ID: {}. Creating a default IDLE status.", locationId);
                    LocationScanStatus newStatus = new LocationScanStatus(location, ScanStatus.IDLE);
                    return locationScanStatusRepository.save(newStatus);
                });
    }
}
