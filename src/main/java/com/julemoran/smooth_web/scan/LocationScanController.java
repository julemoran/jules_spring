package com.julemoran.smooth_web.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

// import java.util.Optional; // No longer needed
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/locations/{locationId}/scan")
public class LocationScanController {

    // Consider adding a @ControllerAdvice for more global exception handling
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Void> handleIllegalStateException(IllegalStateException ex) {
        logger.warn("Handling IllegalStateException: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> handleIllegalArgumentException(IllegalArgumentException ex) {
        logger.warn("Handling IllegalArgumentException: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    private static final Logger logger = LoggerFactory.getLogger(LocationScanController.class);

    private final LocationScanManagementService locationScanManagementService;

    public LocationScanController(LocationScanManagementService locationScanManagementService) {
        this.locationScanManagementService = locationScanManagementService;
    }

    /**
     * Endpoint to start a scan for a specific location.
     * This operation can be long-running, so it's made asynchronous.
     * It returns immediately with 202 Accepted.
     */
    @Async // Spring manages the CompletableFuture execution
    @PostMapping
    public CompletableFuture<ResponseEntity<Void>> startLocationScan(
            @PathVariable Long locationId,
            @RequestParam(defaultValue = "false") boolean calculateHash) {
        logger.info("Controller received async request to scan location ID: {}, calculateHash: {}", locationId, calculateHash);
        // The @Async annotation ensures this method's execution happens in a separate thread.
        // Exceptions thrown by locationScanManagementService.startScan will cause the
        // CompletableFuture to complete exceptionally. These will be handled by the @ExceptionHandler methods.
        locationScanManagementService.startScan(locationId, calculateHash);
        // If startScan completes without throwing, it implies the task was accepted for async execution.
        return CompletableFuture.completedFuture(ResponseEntity.accepted().build());
    }


    /**
     * Endpoint to request abortion of an ongoing scan for a specific location.
     */
    @PostMapping("/abort")
    public ResponseEntity<Void> abortLocationScan(@PathVariable Long locationId) {
        try {
            logger.info("Controller received request to abort scan for location ID: {}", locationId);
            locationScanManagementService.abortScan(locationId);
            return ResponseEntity.ok().build();
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            logger.error("Error aborting scan for location ID {}: {}", locationId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to abort scan: " + e.getMessage(), e);
        }
    }

    /**
     * Endpoint to get the current status of a scan for a specific location.
     */
    @GetMapping("/status")
    public ResponseEntity<LocationScanStatusDto> getLocationScanStatus(@PathVariable Long locationId) {
        try {
            // LocationScanManagementService.getScanStatus now returns LocationScanStatus directly
            // or throws ResponseStatusException if location not found.
            // It creates an IDLE status if one doesn't exist for a valid location.
            LocationScanStatus status = locationScanManagementService.getScanStatus(locationId);
            return ResponseEntity.ok(convertToDto(status));
        } catch (ResponseStatusException rse) {
            throw rse; // Propagate to Spring's default error handling, e.g., for 404
        } catch (Exception e) {
            logger.error("Error retrieving scan status for location ID {}: {}", locationId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to retrieve scan status: " + e.getMessage(), e);
        }
    }

    // Helper to convert Entity to DTO
    private LocationScanStatusDto convertToDto(LocationScanStatus status) {
        LocationScanStatusDto dto = new LocationScanStatusDto();
        dto.setLocationId(status.getLocation().getId());
        dto.setStatus(status.getStatus().name());
        dto.setLastScanStartTime(status.getLastScanStartTime());
        dto.setLastScanEndTime(status.getLastScanEndTime());
        return dto;
    }
}
