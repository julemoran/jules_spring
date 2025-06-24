package com.julemoran.smooth_web.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.julemoran.smooth_web.location.Location;
import com.julemoran.smooth_web.location.LocationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths; // Added this import
import java.time.LocalDateTime;
// import java.util.Comparator; // Removed this import, was unused
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test") // Assuming you have an application-test.properties for test DB, e.g., H2
public class LocationScanIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private ScannedFileRepository scannedFileRepository;

    @Autowired
    private LocationScanStatusRepository locationScanStatusRepository;

    @Autowired
    private ObjectMapper objectMapper; // For JSON conversion

    @TempDir
    Path tempDir; // JUnit 5 temporary directory

    private Location testLocation;
    private Path locationRootPath;

    @BeforeEach
    @Transactional
    void setUp() throws IOException {
        // Clean up database before each test
        scannedFileRepository.deleteAll();
        locationScanStatusRepository.deleteAll();
        locationRepository.deleteAll(); // Deletes locations, which should cascade if set up, or handle manually

        // Create a test location entity
        testLocation = new Location(null, "test-scan-loc", tempDir.resolve("scan_root").toString());
        locationRepository.save(testLocation);

        // Create the physical directory for the location
        locationRootPath = Paths.get(testLocation.getPhysicalPath());
        Files.createDirectories(locationRootPath);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up files, though @TempDir should handle the root.
        // If tests create files outside managed tempDir structure, manual cleanup needed.
        // For this setup, tempDir will be cleaned by JUnit.
        // It's good practice to ensure database cleanup as well, though @Transactional might roll back some changes.
        // Explicit deleteAll is safer for integration tests that commit transactions.
        // The @BeforeEach already handles DB cleanup for the next test.
    }

    private void createTestFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String getExpectedSha256(String content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }


    @Test
    void testStartScan_Success_NoHash() throws Exception {
        // Create some files in the temporary location
        createTestFile(locationRootPath.resolve("file1.txt"), "content1");
        createTestFile(locationRootPath.resolve("subdir/file2.txt"), "content2");

        // 1. Start the scan
        MvcResult asyncResult = mockMvc.perform(post("/locations/" + testLocation.getId() + "/scan")
                        .param("calculateHash", "false"))
                .andExpect(status().isAccepted()) // Endpoint is async, returns 202
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(request().asyncDispatch(asyncResult))
                .andExpect(status().isAccepted()); // Final status after async processing completes for the controller method

        // 2. Poll for scan completion
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            LocationScanStatus status = locationScanStatusRepository.findByLocation(testLocation).orElse(null);
            assertThat(status).isNotNull();
            assertThat(status.getStatus()).isEqualTo(ScanStatus.COMPLETED);
        });

        // 3. Verify ScannedFile entries
        List<ScannedFile> files = scannedFileRepository.findByLocationId(testLocation.getId());
        assertThat(files).hasSize(2);

        ScannedFile file1 = files.stream().filter(f -> f.getFilename().equals("file1.txt")).findFirst().orElse(null);
        assertThat(file1).isNotNull();
        assertThat(file1.getRelativePath()).isEmpty(); // file1.txt is in root
        assertThat(file1.getFileSize()).isEqualTo("content1".getBytes().length);
        assertThat(file1.getSha256Hash()).isNull();

        ScannedFile file2 = files.stream().filter(f -> f.getFilename().equals("file2.txt")).findFirst().orElse(null);
        assertThat(file2).isNotNull();
        assertThat(file2.getRelativePath()).isEqualTo("subdir");
        assertThat(file2.getFileSize()).isEqualTo("content2".getBytes().length);
        assertThat(file2.getSha256Hash()).isNull();
    }

    @Test
    void testStartScan_Success_WithHash() throws Exception {
        String content1 = "Hello World for Hashing";
        String content2 = "Another file with different content";
        createTestFile(locationRootPath.resolve("hashfile1.txt"), content1);
        createTestFile(locationRootPath.resolve("subdir1/hashfile2.txt"), content2);

        String expectedHash1 = getExpectedSha256(content1);
        String expectedHash2 = getExpectedSha256(content2);

        MvcResult asyncResult = mockMvc.perform(post("/locations/" + testLocation.getId() + "/scan")
                        .param("calculateHash", "true"))
                .andExpect(status().isAccepted())
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(request().asyncDispatch(asyncResult))
                .andExpect(status().isAccepted());


        await().atMost(15, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            LocationScanStatus status = locationScanStatusRepository.findByLocation(testLocation).orElse(null);
            assertThat(status).isNotNull();
            assertThat(status.getStatus()).isEqualTo(ScanStatus.COMPLETED);
        });

        List<ScannedFile> files = scannedFileRepository.findByLocationId(testLocation.getId());
        assertThat(files).hasSize(2);

        ScannedFile file1 = files.stream().filter(f -> f.getFilename().equals("hashfile1.txt")).findFirst().orElse(null);
        assertThat(file1).isNotNull();
        assertThat(file1.getSha256Hash()).isEqualTo(expectedHash1);

        ScannedFile file2 = files.stream().filter(f -> f.getFilename().equals("hashfile2.txt")).findFirst().orElse(null);
        assertThat(file2).isNotNull();
        assertThat(file2.getRelativePath()).isEqualTo("subdir1");
        assertThat(file2.getSha256Hash()).isEqualTo(expectedHash2);
    }

    @Test
    void testScan_FileDeletion_RemovedFromDb() throws Exception {
        // First scan with fileA and fileB
        createTestFile(locationRootPath.resolve("fileA.txt"), "contentA");
        createTestFile(locationRootPath.resolve("fileB.txt"), "contentB");

        MvcResult r1 = mockMvc.perform(post("/locations/" + testLocation.getId() + "/scan").param("calculateHash", "false"))
            .andExpect(status().isAccepted()).andReturn();
        mockMvc.perform(request().asyncDispatch(r1)).andExpect(status().isAccepted());
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(locationScanStatusRepository.findByLocation(testLocation).get().getStatus()).isEqualTo(ScanStatus.COMPLETED)
        );
        assertThat(scannedFileRepository.findByLocationId(testLocation.getId())).hasSize(2);

        // Delete fileB.txt physically
        Files.delete(locationRootPath.resolve("fileB.txt"));

        // Second scan
        MvcResult r2 = mockMvc.perform(post("/locations/" + testLocation.getId() + "/scan").param("calculateHash", "false"))
            .andExpect(status().isAccepted()).andReturn();
        mockMvc.perform(request().asyncDispatch(r2)).andExpect(status().isAccepted());
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(locationScanStatusRepository.findByLocation(testLocation).get().getStatus()).isEqualTo(ScanStatus.COMPLETED)
        );

        List<ScannedFile> filesAfterSecondScan = scannedFileRepository.findByLocationId(testLocation.getId());
        assertThat(filesAfterSecondScan).hasSize(1);
        assertThat(filesAfterSecondScan.get(0).getFilename()).isEqualTo("fileA.txt");
    }

    @Test
    void testAbortScan() throws Exception {
        // Create many files to make scan take longer
        for (int i = 0; i < 100; i++) {
            createTestFile(locationRootPath.resolve("file_" + i + ".txt"), "content_" + i);
        }

        // Start scan (with hashing to make it slower)
         MvcResult asyncResult = mockMvc.perform(post("/locations/" + testLocation.getId() + "/scan")
                        .param("calculateHash", "true"))
                .andExpect(status().isAccepted())
                .andExpect(request().asyncStarted())
                .andReturn();
        // Don't wait for controller async part to complete here, proceed to abort

        // Give a very short time for scan to start
        Thread.sleep(200); // Small delay to ensure scan is in RUNNING state

        // Check status is RUNNING (or ABORTING if abort is super fast)
        LocationScanStatus currentStatus = locationScanStatusRepository.findByLocation(testLocation).get();
        assertThat(currentStatus.getStatus()).isIn(ScanStatus.RUNNING, ScanStatus.ABORTING);

        // Abort the scan
        mockMvc.perform(post("/locations/" + testLocation.getId() + "/scan/abort"))
                .andExpect(status().isOk());

        // Dispatch the initial scan request to let its thread complete/handle abort
        try {
            mockMvc.perform(request().asyncDispatch(asyncResult));
        } catch (Exception e) {
            // It's possible the async dispatch itself fails if the underlying process was aborted
            // and led to an exception. This is acceptable in an abort scenario.
        }


        // Poll for scan status to become ABORTED
        await().atMost(15, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            LocationScanStatus status = locationScanStatusRepository.findByLocation(testLocation).orElse(null);
            assertThat(status).isNotNull();
            assertThat(status.getStatus()).isEqualTo(ScanStatus.ABORTED);
        });

        // Verify no files were persisted
        List<ScannedFile> files = scannedFileRepository.findByLocationId(testLocation.getId());
        assertThat(files).isEmpty();
    }

    @Test
    void testGetScanStatus() throws Exception {
        // 1. Initial status should now be IDLE as the service creates it.
        MvcResult initialResult = mockMvc.perform(get("/locations/" + testLocation.getId() + "/scan/status"))
                .andExpect(status().isOk())
                .andReturn();
        LocationScanStatusDto initialStatusDto = objectMapper.readValue(initialResult.getResponse().getContentAsString(), LocationScanStatusDto.class);
        assertThat(initialStatusDto.getStatus()).isEqualTo(ScanStatus.IDLE.name());
        assertThat(initialStatusDto.getLocationId()).isEqualTo(testLocation.getId());
        assertThat(initialStatusDto.getLastScanStartTime()).isNull();
        assertThat(initialStatusDto.getLastScanEndTime()).isNull();

        // Start a scan
        createTestFile(locationRootPath.resolve("status_test.txt"), "test");
        MvcResult asyncResult = mockMvc.perform(post("/locations/" + testLocation.getId() + "/scan")
                        .param("calculateHash", "false"))
                .andExpect(status().isAccepted())
                .andReturn();

        // Wait for the async controller part to finish submitting the task
        mockMvc.perform(request().asyncDispatch(asyncResult)).andExpect(status().isAccepted());

        // Check status while RUNNING (or just after accepted)
        // This is a bit racy, but we expect it to be RUNNING shortly after starting.
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            MvcResult result = mockMvc.perform(get("/locations/" + testLocation.getId() + "/scan/status"))
                    .andExpect(status().isOk())
                    .andReturn();
            LocationScanStatusDto statusDto = objectMapper.readValue(result.getResponse().getContentAsString(), LocationScanStatusDto.class);
            assertThat(statusDto.getStatus()).isIn(ScanStatus.RUNNING.name(), ScanStatus.COMPLETED.name()); // Could be very fast
        });


        // Wait for completion
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            MvcResult result = mockMvc.perform(get("/locations/" + testLocation.getId() + "/scan/status"))
                    .andExpect(status().isOk())
                    .andReturn();
            LocationScanStatusDto statusDto = objectMapper.readValue(result.getResponse().getContentAsString(), LocationScanStatusDto.class);
            assertThat(statusDto.getStatus()).isEqualTo(ScanStatus.COMPLETED.name());
            assertThat(statusDto.getLocationId()).isEqualTo(testLocation.getId());
            assertThat(statusDto.getLastScanStartTime()).isNotNull();
            assertThat(statusDto.getLastScanEndTime()).isNotNull();
        });
    }

    @Test
    void testPreventParallelScans() throws Exception {
        createTestFile(locationRootPath.resolve("parallel_test.txt"), "test");

        // Start the first scan (make it potentially long with hashing)
        MvcResult asyncResult1 = mockMvc.perform(post("/locations/" + testLocation.getId() + "/scan")
                        .param("calculateHash", "true"))
                .andExpect(status().isAccepted())
                .andExpect(request().asyncStarted())
                .andReturn();

        // Don't wait for the controller's async part to fully complete its internal thread yet.
        // The goal is to make the second call while the first is (supposedly) RUNNING.

        Thread.sleep(100); // Brief pause to allow the first scan to enter RUNNING state in DB

        // Attempt to start a second scan for the same location
        MvcResult asyncResult2 = mockMvc.perform(post("/locations/" + testLocation.getId() + "/scan")
                        .param("calculateHash", "false"))
                .andExpect(status().isAccepted()) // Controller is async, will accept.
                .andExpect(request().asyncStarted())
                .andReturn();

        // Now, dispatch the second request. This is where the CONFLICT should occur.
        mockMvc.perform(request().asyncDispatch(asyncResult2))
                .andExpect(status().isConflict());

        // Allow the first scan to complete
        mockMvc.perform(request().asyncDispatch(asyncResult1))
                .andExpect(status().isAccepted()); // Original scan should be accepted.

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(locationScanStatusRepository.findByLocation(testLocation).get().getStatus()).isEqualTo(ScanStatus.COMPLETED)
        );

        // Verify only one set of files (from the first scan)
        assertThat(scannedFileRepository.findByLocationId(testLocation.getId())).hasSize(1);
    }
}
