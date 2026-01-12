package com.julemoran.smooth_web.scan.hashing;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface FileHasher {
    /**
     * Calculates the hash of the given file asynchronously.
     *
     * @param filePath The path to the file.
     * @return A CompletableFuture that will complete with the hex-encoded hash string,
     *         or complete exceptionally if an error occurs.
     */
    CompletableFuture<String> calculateHash(Path filePath);
}
