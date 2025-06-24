package com.julemoran.smooth_web.scan;

import com.julemoran.smooth_web.location.Location;
import com.julemoran.smooth_web.scan.hashing.FileHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
// Removed @Transactional imports as this service will no longer manage transactions directly for scan lifecycle

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
// import java.util.concurrent.atomic.AtomicBoolean; // Abort flag will be managed by ScanStateManager
// import java.util.stream.Collectors; // Not used

@Service
public class FileScannerService {

    private static final Logger logger = LoggerFactory.getLogger(FileScannerService.class);

    // Repositories are no longer directly used here for status/final file saving.
    // private final LocationScanStatusRepository locationScanStatusRepository;
    // private final ScannedFileRepository scannedFileRepository;
    private final FileHasher fileHasher;

    // Abort flags are now managed by ScanStateManager
    // private final ConcurrentHashMap<Long, AtomicBoolean> abortFlags = new ConcurrentHashMap<>();


    public FileScannerService(@Qualifier("javaSha256Hasher") FileHasher fileHasher) {
        // Repositories related to scan status and scanned files are removed from constructor
        this.fileHasher = fileHasher;
    }

    /**
     * Performs the actual file scanning for a given location.
     * This method is designed to be called asynchronously.
     * It reports its progress and final state (completed, failed, aborted)
     * back to the ScanStateManager.
     *
     * @param location The Location entity to scan.
     * @param calculateHash Whether to calculate SHA256 hashes for files.
     * @param locationId The ID of the location, used for interacting with ScanStateManager.
     * @param stateManager The ScanStateManager to check for abort requests and report final status.
     */
    public void performActualScan(Location location, boolean calculateHash, Long locationId, ScanStateManager stateManager) {
        LocalDateTime startTime = LocalDateTime.now(); // Record actual start time of this intensive part
        // Confirm with state manager that this scan is indeed starting its main work under RUNNING state
        // This also ensures the in-memory state is correctly RUNNING if it wasn't already.
        stateManager.confirmScanActuallyStarted(locationId);


        logger.info("FileScannerService: Starting actual scan for location ID: {} with calculateHash={}", locationId, calculateHash);

        List<ScannedFile> scannedFilesBatch = new ArrayList<>();
        List<CompletableFuture<Void>> hashFutures = new ArrayList<>();
        Path rootPath = Paths.get(location.getPhysicalPath());

        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            logger.error("Physical path for location {} does not exist or is not a directory: {}", location.getName(), rootPath);
            stateManager.recordScanFailed(locationId, startTime, LocalDateTime.now());
            return;
        }

        // Clearing of previous scan data is now handled by ScanStateManager before saving new files.

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (stateManager.isAbortRequested(locationId)) {
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
                    if (stateManager.isAbortRequested(locationId)) {
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                     if (stateManager.isAbortRequested(locationId)) {
                        logger.info("Scan for location ID: {} aborted during directory post-visit.", locationId);
                        return FileVisitResult.TERMINATE;
                    }
                    if (exc != null) {
                        logger.warn("Error after visiting directory {}: {}", dir, exc.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (calculateHash && !hashFutures.isEmpty()) {
                logger.info("Waiting for {} hash calculations to complete for location ID: {}", hashFutures.size(), locationId);
                CompletableFuture<Void> allHashesFuture = CompletableFuture.allOf(hashFutures.toArray(new CompletableFuture[0]));
                try {
                    allHashesFuture.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Hash calculation interrupted for location ID: {}", locationId, e);
                    if (stateManager.isAbortRequested(locationId)) {
                         stateManager.recordScanAborted(locationId, startTime, LocalDateTime.now());
                    } else {
                        stateManager.recordScanFailed(locationId, startTime, LocalDateTime.now());
                    }
                    return; // Exit early
                } catch (ExecutionException e) {
                    logger.error("Error during hash calculation for location ID: {}", locationId, e.getCause());
                    // This implies a file hashing failed, but we might still complete the scan with other files.
                    // Individual errors are logged by the exceptionally block.
                    // If this is critical, we might mark the whole scan as FAILED. For now, assume it continues.
                }
            }

            if (stateManager.isAbortRequested(locationId)) {
                logger.info("Scan for location ID: {} was aborted. Discarding {} collected file entries.", locationId, scannedFilesBatch.size());
                stateManager.recordScanAborted(locationId, startTime, LocalDateTime.now());
            } else {
                logger.info("Scan for location ID: {} completed. Passing {} file entries to state manager.", locationId, scannedFilesBatch.size());
                stateManager.recordScanCompleted(locationId, startTime, LocalDateTime.now(), scannedFilesBatch);
            }

        } catch (IOException e) {
            logger.error("IOException during file scanning for location ID: {}: {}", locationId, e.getMessage(), e);
            if (stateManager.isAbortRequested(locationId)) {
                 stateManager.recordScanAborted(locationId, startTime, LocalDateTime.now());
            } else {
                stateManager.recordScanFailed(locationId, startTime, LocalDateTime.now());
            }
        } catch (Exception e) { // Catch any other unexpected errors
            logger.error("Unexpected error during file scanning for location ID: {}: {}", locationId, e.getMessage(), e);
             if (stateManager.isAbortRequested(locationId)) {
                 stateManager.recordScanAborted(locationId, startTime, LocalDateTime.now());
            } else {
                stateManager.recordScanFailed(locationId, startTime, LocalDateTime.now());
            }
        } finally {
            // In-memory state (like abort flag) cleanup is handled by ScanStateManager upon terminal status update.
            logger.info("FileScannerService.performActualScan finished for location ID: {}.", locationId);
        }
    }

    // clearPreviousScanData is removed, ScanStateManager.recordScanCompleted will handle this.
    // requestAbortScan is removed, ScanStateManager.requestAbort directly handles this.
}
