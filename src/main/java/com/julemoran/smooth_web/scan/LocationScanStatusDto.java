package com.julemoran.smooth_web.scan;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationScanStatusDto {
    private Long locationId;
    private String status; // Using String to represent Enum for flexibility in API
    private LocalDateTime lastScanStartTime;
    private LocalDateTime lastScanEndTime;
}
