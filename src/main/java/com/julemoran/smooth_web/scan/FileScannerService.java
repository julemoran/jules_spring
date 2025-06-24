package com.julemoran.smooth_web.scan;

import com.julemoran.smooth_web.location.Location;
import com.julemoran.smooth_web.scan.hashing.FileHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class FileScannerService {

    private static final Logger logger = LoggerFactory.getLogger(FileScannerService.class);

    private final LocationScanStatusRepository locationScanStatusRepository;
    private final ScannedFileRepository scannedFileRepository;
    private final FileHasher fileHasher;

    // Using a simple in-memory flag for abort signaling.
    // For multi-node deployments, a distributed cache/DB flag would be needed.
    private final ConcurrentHashMap<Long, AtomicBoolean> abortFlags = new ConcurrentHashMap<>();


    public FileScannerService(LocationScanStatusRepository locationScanStatusRepository,
                              ScannedFileRepository scannedFileRepository,
                              @Qualifier("javaSha256Hasher") FileHasher fileHasher) {
        this.locationScanStatusRepository = locationScanStatusRepository;
        this.scannedFileRepository = scannedFileRepository;
        this.fileHasher = fileHasher;
    }

    // This method will be called by LocationScanManagementService, wrapped in its own transaction.
    // It handles the core scanning logic.
    @Transactional(propagation = Propagation.REQUIRES_NEW) // New transaction for each scan process
    public void scanLocation(Location location, boolean calculateHash) {
        Long locationId = location.getId();
        logger.info("Attempting to scan location ID: {} with calculateHash={}", locationId, calculateHash);

        LocationScanStatus status = locationScanStatusRepository.findByLocation(location)
                .orElseGet(() -> new LocationScanStatus(location, ScanStatus.IDLE));

        if (status.getStatus() == ScanStatus.RUNNING || status.getStatus() == ScanStatus.ABORTING) {
            logger.warn("Scan for location ID: {} is already {} or {}. Aborting new scan request.",
                    locationId, ScanStatus.RUNNING, ScanStatus.ABORTING);
            // Potentially throw an exception or return a status
            return;
        }

        status.setStatus(ScanStatus.RUNNING);
        status.setLastScanStartTime(LocalDateTime.now());
        status.setLastScanEndTime(null);
        locationScanStatusRepository.save(status);

        // Initialize abort flag for this scan
        AtomicBoolean currentScanAbortFlag = abortFlags.computeIfAbsent(locationId, k -> new AtomicBoolean(false));
        currentScanAbortFlag.set(false); // Reset if it was true from a previous aborted scan

        List<ScannedFile> scannedFilesBatch = new ArrayList<>();
        List<CompletableFuture<Void>> hashFutures = new ArrayList<>();
        Path rootPath = Paths.get(location.getPhysicalPath());

        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            logger.error("Physical path for location {} does not exist or is not a directory: {}", location.getName(), rootPath);
            status.setStatus(ScanStatus.FAILED);
            status.setLastScanEndTime(LocalDateTime.now());
            locationScanStatusRepository.save(status);
            abortFlags.remove(locationId);
            return;
        }

        // Clean up previous scan data for this location before starting a new scan
        // This ensures that if a file is deleted from the source, it's removed from our records.
        // Doing this in a separate transaction to ensure it completes before new data is added.
        clearPreviousScanData(locationId);

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (currentScanAbortFlag.get()) {
                        logger.info("Scan for location ID: {} aborted during file visit.", locationId);
                        return FileVisitResult.TERMINATE;
                    }

                    if (attrs.isRegularFile()) {
                        Path relativePath = rootPath.relativize(file.getParent() != null ? file.getParent() : rootPath);
                        String filename = file.getFileName().toString();
                        long fileSize = attrs.size();
                        LocalDateTime creationDate = LocalDateTime.ofInstant(attrs.creationTime().toInstant(), ZoneId.systemDefault());
                        LocalDateTime lastModifiedDate = LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault());

                        ScannedFile scannedFile = new ScannedFile(null, location, relativePath.toString(), filename, fileSize, creationDate, lastModifiedDate, null);

                        if (calculateHash) {
                            CompletableFuture<String> hashFuture = fileHasher.calculateHash(file);
                            hashFutures.add(hashFuture.thenAccept(scannedFile::setSha256Hash)
                                .exceptionally(ex -> {
                                    logger.error("Error calculating hash for file {}: {}", file, ex.getMessage());
                                    // Decide if this should fail the file or the scan
                                    // For now, we'll just log and continue without a hash
                                    return null;
                                }));
                        }
                        scannedFilesBatch.add(scannedFile);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    logger.warn("Failed to visit file {}: {}", file, exc.getMessage());
                    // Check abort status even on failure
                    if (currentScanAbortFlag.get()) {
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE; // Continue scanning other files
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                     if (currentScanAbortFlag.get()) {
                        logger.info("Scan for location ID: {} aborted during directory post-visit.", locationId);
                        return FileVisitResult.TERMINATE;
                    }
                    if (exc != null) {
                        logger.warn("Error after visiting directory {}: {}", dir, exc.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            // Wait for all hash calculations to complete
            if (calculateHash && !hashFutures.isEmpty()) {
                logger.info("Waiting for {} hash calculations to complete for location ID: {}", hashFutures.size(), locationId);
                CompletableFuture<Void> allHashesFuture = CompletableFuture.allOf(hashFutures.toArray(new CompletableFuture[0]));
                try {
                    allHashesFuture.get(); // Wait for completion
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Hash calculation interrupted for location ID: {}", locationId, e);
                    status.setStatus(ScanStatus.FAILED); // Or potentially ABORTED if interruption is due to abort
                    // Re-check abort flag
                    if (currentScanAbortFlag.get()) {
                         status.setStatus(ScanStatus.ABORTED);
                    }
                } catch (ExecutionException e) {
                    logger.error("Error during hash calculation for location ID: {}", locationId, e.getCause());
                    // Decide if this fails the entire scan or just logs errors per file (handled in exceptionally above)
                    // For now, assuming per-file errors are logged and scan continues. If a global error, then:
                    // status.setStatus(ScanStatus.FAILED);
                }
            }

            if (currentScanAbortFlag.get()) { // Check flag again before saving
                logger.info("Scan for location ID: {} was aborted. Discarding {} collected file entries.", locationId, scannedFilesBatch.size());
                status.setStatus(ScanStatus.ABORTED);
            } else {
                logger.info("Scan for location ID: {} completed. Saving {} file entries.", locationId, scannedFilesBatch.size());
                if (!scannedFilesBatch.isEmpty()) {
                    scannedFileRepository.saveAll(scannedFilesBatch); // Batch save
                }
                status.setStatus(ScanStatus.COMPLETED);
            }

        } catch (IOException e) {
            logger.error("IOException during file scanning for location ID: {}: {}", locationId, e.getMessage(), e);
            status.setStatus(ScanStatus.FAILED);
             if (currentScanAbortFlag.get()) { // If IOE happened during an abort sequence
                status.setStatus(ScanStatus.ABORTED);
            }
        } catch (Exception e) {
            logger.error("Unexpected error during file scanning for location ID: {}: {}", locationId, e.getMessage(), e);
            status.setStatus(ScanStatus.FAILED);
             if (currentScanAbortFlag.get()) {
                status.setStatus(ScanStatus.ABORTED);
            }
        } finally {
            status.setLastScanEndTime(LocalDateTime.now());
            locationScanStatusRepository.save(status);
            abortFlags.remove(locationId); // Clean up the flag for this scan
            logger.info("Scan process finished for location ID: {}. Final status: {}", locationId, status.getStatus());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void clearPreviousScanData(Long locationId) {
        logger.info("Clearing previous scan data for location ID: {}", locationId);
        scannedFileRepository.deleteByLocationId(locationId);
        logger.info("Successfully cleared previous scan data for location ID: {}", locationId);
    }


    // This method is called by LocationScanManagementService to signal an abort.
    public void requestAbortScan(Long locationId) {
        logger.info("Received abort request for scan of location ID: {}", locationId);
        LocationScanStatus status = locationScanStatusRepository.findByLocationId(locationId).orElse(null);
        if (status != null && status.getStatus() == ScanStatus.RUNNING) {
            status.setStatus(ScanStatus.ABORTING);
            locationScanStatusRepository.save(status);
            AtomicBoolean flag = abortFlags.get(locationId);
            if (flag != null) {
                flag.set(true);
                logger.info("Abort flag set for location ID: {}", locationId);
            } else {
                logger.warn("No active scan found to abort for location ID: {} (abort flag missing), but status was RUNNING. Setting status to ABORTING.", locationId);
            }
        } else if (status != null) {
            logger.warn("Scan for location ID: {} is not in RUNNING state (current: {}). Cannot abort.", locationId, status.getStatus());
        } else {
            logger.warn("No scan status found for location ID: {}. Cannot abort.", locationId);
        }
    }
}
