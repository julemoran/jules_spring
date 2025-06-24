package com.julemoran.smooth_web.scan;

import com.julemoran.smooth_web.location.Location;
import com.julemoran.smooth_web.location.LocationRepository;
import com.julemoran.smooth_web.scan.hashing.FileHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class LocationScanManagementService {

    private static final Logger logger = LoggerFactory.getLogger(LocationScanManagementService.class);

    private final LocationRepository locationRepository;
    private final ScanStateManager scanStateManager;
    private final FileHasher fileHasher; // Needed to create FileScanTask
    private final ExecutorService scanExecutor; // For running scan tasks asynchronously
    private final ConcurrentHashMap<Long, Future<ScanResult>> activeScanFutures = new ConcurrentHashMap<>();

    @Autowired
    public LocationScanManagementService(LocationRepository locationRepository,
                                         ScanStateManager scanStateManager,
                                         @Qualifier("javaSha256Hasher") FileHasher fileHasher) {
        this.locationRepository = locationRepository;
        this.scanStateManager = scanStateManager;
        this.fileHasher = fileHasher;
        // Using a cached thread pool, but a fixed-size pool might be better for production
        this.scanExecutor = Executors.newCachedThreadPool();
    }

    public void startScan(Long locationId, boolean calculateHash) {
        Location location = locationRepository.findById(locationId)
                .orElseThrow(() -> new IllegalArgumentException("Location not found with ID: " + locationId));

        logger.info("Attempting to start scan for location: {} (ID: {}), calculateHash: {}", location.getName(), locationId, calculateHash);

        if (!scanStateManager.canStartScan(locationId)) {
            // This check includes if a scan is already active OR if an abort has been requested but not yet finalized.
            throw new IllegalStateException("Scan for location " + location.getName() + " cannot be started. It might be already in progress, or an abort is being processed.");
        }

        // Record scan as IN_PROGRESS. The actual task start time will be in ScanResult.
        // This time is more of "submission time".
        scanStateManager.recordScanInProgress(locationId, LocalDateTime.now());

        IScanTask scanTask = new FileScanTask(location, calculateHash, fileHasher);

        // scanTask::runScan is a Callable<ScanResult>
        Future<ScanResult> futureResult = scanExecutor.submit(scanTask::runScan);
        activeScanFutures.put(locationId, futureResult);

        // Use CompletableFuture to handle the result asynchronously without blocking the main thread
        // that called startScan. This separate task will wait for futureResult.
        CompletableFuture.runAsync(() -> {
            ScanResult scanResult = null;
            try {
                // Wait for the scan task to complete.
                // This blocks the thread managed by CompletableFuture.runAsync, not the caller of startScan.
                scanResult = futureResult.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Scan task future.get() was interrupted for location ID: {}. Likely due to abort.", locationId, e);
                // If interrupted, it's highly likely an abort was requested.
                // The scanTask itself should ideally produce an ABORTED ScanResult.
                // If scanResult is null here, it means future.get() was interrupted before result was set.
                if (scanStateManager.isAbortRequested(locationId)) {
                     scanStateManager.recordScanAborted(locationId, null, LocalDateTime.now());
                } else {
                    scanStateManager.recordScanFailed(locationId, null, LocalDateTime.now());
                }
            } catch (java.util.concurrent.ExecutionException e) {
                logger.error("Scan task execution failed for location ID: {}", locationId, e.getCause());
                // The task itself should return a FAILED ScanResult. This path handles underlying execution issues.
                 scanStateManager.recordScanFailed(locationId, null, LocalDateTime.now());
            } catch (java.util.concurrent.CancellationException e) {
                logger.info("Scan task was cancelled for location ID: {}. Likely due to abort.", locationId);
                // This happens if future.cancel(true) was called and succeeded.
                // The scanTask itself should ideally produce an ABORTED ScanResult.
                // If scanResult is null here, it means future.get() was interrupted before result was set.
                scanStateManager.recordScanAborted(locationId, null, LocalDateTime.now());
            } finally {
                activeScanFutures.remove(locationId);
                LocalDateTime endTime = LocalDateTime.now(); // Use this as a fallback if scanResult.endTime is not available

                if (scanResult != null) {
                    switch (scanResult.finalStatus()) {
                        case COMPLETED:
                            scanStateManager.recordScanCompleted(locationId, scanResult.startTime(), scanResult.endTime(), scanResult.discoveredFiles());
                            break;
                        case FAILED:
                            scanStateManager.recordScanFailed(locationId, scanResult.startTime(), scanResult.endTime());
                            break;
                        case ABORTED:
                            scanStateManager.recordScanAborted(locationId, scanResult.startTime(), scanResult.endTime());
                            break;
                        default: // Should not happen
                            logger.error("Scan for location ID {} ended with unexpected ScanResult status: {}. Marking as FAILED.", locationId, scanResult.finalStatus());
                            scanStateManager.recordScanFailed(locationId, scanResult.startTime(), scanResult.endTime() != null ? scanResult.endTime() : endTime);
                            break;
                    }
                } else {
                    // If scanResult is null, it means future.get() didn't complete normally.
                    // The specific catch blocks (InterruptedException, ExecutionException, CancellationException)
                    // should have already updated the status. This is a fallback/double-check.
                    // However, if it reaches here and status wasn't set, it's an issue.
                    // Re-check abort status as a priority.
                    if (scanStateManager.isAbortRequested(locationId) && scanStateManager.getPersistedScanStatus(locationId).getStatus() != ScanStatus.ABORTED) {
                         logger.warn("ScanResult was null for location {}, and abort was requested. Ensuring status is ABORTED.", locationId);
                         scanStateManager.recordScanAborted(locationId, null, endTime);
                    } else if (scanStateManager.getPersistedScanStatus(locationId).getStatus() == ScanStatus.RUNNING) {
                        // If still RUNNING, it means something went wrong and no specific exception handled it.
                        logger.error("Scan task for location ID {} had null result and no specific exception caught to update status from RUNNING. Marking as FAILED.", locationId);
                        scanStateManager.recordScanFailed(locationId, null, endTime);
                    }
                }
            }
        }, scanExecutor); // You can use the same executor or a different one for these handlers
    }

    public void abortScan(Long locationId) {
        if (!locationRepository.existsById(locationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Location not found with ID: " + locationId);
        }
        logger.info("Request received to abort scan for location ID: {}", locationId);

        // Signal ScanStateManager first to update in-memory state and potentially DB to ABORTING
        scanStateManager.requestAbort(locationId);

        // Then, attempt to cancel the future task if it's still running
        Future<ScanResult> future = activeScanFutures.get(locationId);
        if (future != null && !future.isDone()) {
            boolean cancelled = future.cancel(true); // Attempt to interrupt the thread
            logger.info("Attempt to cancel scan future for location ID {}: {}", locationId, cancelled ? "successful" : "failed or already done");
        } else {
            logger.info("No active scan future found for location ID {} to cancel, or it was already done.", locationId);
        }
        // The IScanTask itself (if accessible) would also have its requestAbort() called by ScanStateManager
        // or directly here if we stored IScanTask instances. The current ScanStateManager.requestAbort handles the flag.
    }

    @Transactional
    public LocationScanStatus getScanStatus(Long locationId) {
        return scanStateManager.getPersistedScanStatus(locationId);
    }
}
