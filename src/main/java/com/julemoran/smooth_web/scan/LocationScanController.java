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
    @Async // Make the controller method asynchronous
    @PostMapping
    public CompletableFuture<ResponseEntity<Void>> startLocationScan(
            @PathVariable Long locationId,
            @RequestParam(defaultValue = "false") boolean calculateHash) {

        return CompletableFuture.runAsync(() -> {
            try {
                logger.info("Controller received request to scan location ID: {}, calculateHash: {}", locationId, calculateHash);
                locationScanManagementService.startScan(locationId, calculateHash);
                // If startScan completes without throwing an exception handled by itself, it means it was accepted.
                // The actual scan runs in the background.
            } catch (ResponseStatusException rse) {
                // Re-throw to be handled by Spring's exception handling, converting to appropriate HTTP response
                throw rse;
            } catch (Exception e) {
                logger.error("Unexpected error during startScan call for location ID {}: {}", locationId, e.getMessage(), e);
                // For unhandled exceptions from the service call (that weren't ResponseStatusException)
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to start scan: " + e.getMessage(), e);
            }
        }).thenApply(voidResult -> ResponseEntity.accepted().build())
          .exceptionally(ex -> {
              if (ex.getCause() instanceof ResponseStatusException) {
                  ResponseStatusException rse = (ResponseStatusException) ex.getCause();
                  return ResponseEntity.status(rse.getStatusCode()).<Void>build(); // Explicitly type the response
              }
              logger.error("Async scan initiation failed for location ID {}: {}", locationId, ex.getMessage(), ex);
              return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).<Void>build(); // Explicitly type the response
          });
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
