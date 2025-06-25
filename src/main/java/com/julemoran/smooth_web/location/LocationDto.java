package com.julemoran.smooth_web.location;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationDto {

    private Long id; // Added ID field
    private String name;
    private String physicalPath;
}
