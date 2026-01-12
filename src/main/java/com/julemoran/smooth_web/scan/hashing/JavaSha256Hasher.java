package com.julemoran.smooth_web.scan.hashing;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;

@Component("javaSha256Hasher") // Qualify the bean name
public class JavaSha256Hasher implements FileHasher {

    private static final int BUFFER_SIZE = 8192; // 8KB buffer

    @Async // Make this method execute asynchronously
    @Override
    public CompletableFuture<String> calculateHash(Path filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream fis = Files.newInputStream(filePath)) {
                byte[] byteArray = new byte[BUFFER_SIZE];
                int bytesCount;
                while ((bytesCount = fis.read(byteArray)) != -1) {
                    digest.update(byteArray, 0, bytesCount);
                }
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte aByte : bytes) {
                sb.append(Integer.toString((aByte & 0xff) + 0x100, 16).substring(1));
            }
            return CompletableFuture.completedFuture(sb.toString());
        } catch (Exception e) {
            // Log the error or handle it more gracefully
            // For now, returning a CompletableFuture that completes exceptionally
            return CompletableFuture.failedFuture(e);
        }
    }
}
