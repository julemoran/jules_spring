package com.julemoran.smooth_web.location;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationDto {

    private Long id; // Added id field
    private String name;
    private String physicalPath;

    // Constructor for creating DTO without ID (e.g., for creation requests)
    public LocationDto(String name, String physicalPath) {
        this.name = name;
        this.physicalPath = physicalPath;
    }
}
