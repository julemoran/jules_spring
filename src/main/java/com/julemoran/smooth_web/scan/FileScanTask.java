package com.julemoran.smooth_web.scan;

import com.julemoran.smooth_web.location.Location;
import com.julemoran.smooth_web.scan.hashing.FileHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture; // Keep for hashFutures
import java.util.concurrent.ExecutionException;   // Keep for hashFutures.get()
import java.util.concurrent.atomic.AtomicBoolean;
// Removed: import java.util.concurrent.Future; // No longer returning Future directly from runScan

public class FileScanTask implements IScanTask {
    private static final Logger logger = LoggerFactory.getLogger(FileScanTask.class);

    private final Location location;
    private final boolean calculateHash;
    private final FileHasher fileHasher;
    private final AtomicBoolean abortFlag = new AtomicBoolean(false);
    private final LocalDateTime taskCreationTime; // Renamed for clarity

    // For testing purposes only, to artificially slow down scans
    public static long TEST_ONLY_ARTIFICIAL_DELAY_MS = 0;


    public FileScanTask(Location location, boolean calculateHash, FileHasher fileHasher) {
        this.location = location;
        this.calculateHash = calculateHash;
        this.fileHasher = fileHasher;
        this.taskCreationTime = LocalDateTime.now();
    }

    @Override
    public ScanResult runScan() {
        LocalDateTime scanActualStartTime = LocalDateTime.now();
        try {
            logger.info("FileScanTask runScan() started for location ID: {}, Path: {}", location.getId(), location.getPhysicalPath());

            if (TEST_ONLY_ARTIFICIAL_DELAY_MS > 0) {
                try {
                    logger.info("Artificially delaying FileScanTask by {} ms for testing.", TEST_ONLY_ARTIFICIAL_DELAY_MS);
                    Thread.sleep(TEST_ONLY_ARTIFICIAL_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("FileScanTask artificial delay interrupted for location ID: {}", location.getId());
                    // If interrupted during test delay, might as well consider it an abort for safety in test.
                    return ScanResult.aborted(scanActualStartTime, LocalDateTime.now());
                }
            }

            if (isAbortRequested()) { // Check abort status early, especially after potential delay
                logger.info("Scan (task) for location ID: {} aborted before processing files.", location.getId());
                return ScanResult.aborted(scanActualStartTime, LocalDateTime.now());
            }

            Path rootPath = Paths.get(location.getPhysicalPath());
            if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
                logger.error("Physical path for location {} does not exist or is not a directory: {}", location.getName(), rootPath);
                return ScanResult.failed("Physical path does not exist or is not a directory.", scanActualStartTime, LocalDateTime.now());
            }

            List<ScannedFile> scannedFilesBatch = new ArrayList<>();
            // Still use CompletableFuture for individual hash calculations if enabled
            List<CompletableFuture<Void>> hashFutures = calculateHash ? new ArrayList<>() : null;


            Files.walkFileTree(rootPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (isAbortRequested()) {
                        logger.info("Scan (task) for location ID: {} aborted during file visit.", location.getId());
                        return FileVisitResult.TERMINATE;
                    }

                    if (attrs.isRegularFile()) {
                        Path relativePathDir = rootPath.relativize(file.getParent() != null ? file.getParent() : rootPath);
                        String filename = file.getFileName().toString();
                        long fileSize = attrs.size();
                        LocalDateTime creationDate = LocalDateTime.ofInstant(attrs.creationTime().toInstant(), ZoneId.systemDefault());
                        LocalDateTime lastModifiedDate = LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault());

                        ScannedFile scannedFile = new ScannedFile(null, null, relativePathDir.toString(), filename, fileSize, creationDate, lastModifiedDate, null);

                        if (calculateHash && hashFutures != null) {
                            CompletableFuture<String> hashFuture = fileHasher.calculateHash(file);
                            hashFutures.add(hashFuture.thenAccept(scannedFile::setSha256Hash)
                                .exceptionally(ex -> {
                                    logger.error("Error calculating hash for file {}: {}", file, ex.getMessage());
                                    // Mark hash as null or some error indicator if needed, or just log
                                    scannedFile.setSha256Hash(null); // Explicitly set to null on error
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
                    if (isAbortRequested()) {
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                     if (isAbortRequested()) {
                        logger.info("Scan (task) for location ID: {} aborted during directory post-visit.", location.getId());
                        return FileVisitResult.TERMINATE;
                    }
                    if (exc != null) {
                        logger.warn("Error after visiting directory {}: {}", dir, exc.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (isAbortRequested()) {
                logger.info("Scan (task) for location ID: {} was aborted after walking tree (or during). Discarding files.", location.getId());
                return ScanResult.aborted(scanActualStartTime, LocalDateTime.now());
            }

            // Wait for all hash calculations to complete if they were started
            if (calculateHash && hashFutures != null && !hashFutures.isEmpty()) {
                logger.info("Waiting for {} hash calculations to complete for location ID: {}", hashFutures.size(), location.getId());
                CompletableFuture<Void> allHashesFuture = CompletableFuture.allOf(hashFutures.toArray(new CompletableFuture[0]));
                try {
                    allHashesFuture.get(); // Wait for completion
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Hash calculation interrupted for location ID: {}", location.getId(), e);
                    if (isAbortRequested()) {
                        return ScanResult.aborted(scanActualStartTime, LocalDateTime.now());
                    } else {
                        return ScanResult.failed("Hash calculation interrupted.", scanActualStartTime, LocalDateTime.now());
                    }
                } catch (ExecutionException e) {
                    // Log the cause, but individual errors are already handled in the exceptionally block.
                    // The scan can still be considered "completed" but with potential missing hashes.
                    logger.warn("One or more hash calculations failed for location ID: {}. See previous logs.", location.getId(), e.getCause());
                }
            }

            if (isAbortRequested()) { // Final check before completing successfully
                logger.info("Scan (task) for location ID: {} was aborted before final completion. Discarding files.", location.getId());
                return ScanResult.aborted(scanActualStartTime, LocalDateTime.now());
            } else {
                logger.info("Scan (task) for location ID: {} completed. Found {} files.", location.getId(), scannedFilesBatch.size());
                return ScanResult.completed(scannedFilesBatch, scanActualStartTime, LocalDateTime.now());
            }

        } catch (IOException e) {
            logger.error("IOException during file scanning for location ID {}: {}", location.getId(), e.getMessage(), e);
            return ScanResult.failed("IOException: " + e.getMessage(), scanActualStartTime, LocalDateTime.now());
        } catch (Exception e) { // Catch-all for other unexpected errors
            logger.error("Unexpected error during file scanning for location ID {}: {}", location.getId(), e.getMessage(), e);
            return ScanResult.failed("Unexpected error: " + e.getMessage(), scanActualStartTime, LocalDateTime.now());
        }
        // Unreachable in current structure, but as a fallback:
        // return ScanResult.failed("Reached end of runScan unexpectedly.", scanActualStartTime, LocalDateTime.now());
    }

    @Override
    public void requestAbort() {
        logger.info("Abort explicitly requested for FileScanTask of location ID: {}", location != null ? location.getId() : "UNKNOWN");
        this.abortFlag.set(true);
    }

    @Override
    public boolean isAbortRequested() {
        return this.abortFlag.get();
    }
}
