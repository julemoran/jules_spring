package com.julemoran.smooth_web.location;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/locations")
public class LocationController {

    private final LocationRepository locationRepository;

    @Autowired
    public LocationController(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    private LocationDto convertToDto(Location location) {
        // Use the constructor that includes the id
        return new LocationDto(location.getId(), location.getName(), location.getPhysicalPath());
    }

    private Location convertToEntity(LocationDto locationDto) {
        // For updates, we might need to fetch existing entity first,
        // but for creation, a new entity is fine.
        // ID is auto-generated or handled by JPA.
        return new Location(null, locationDto.getName(), locationDto.getPhysicalPath());
    }

    @PostMapping
    public ResponseEntity<LocationDto> addLocation(@RequestBody LocationDto locationDto) {
        if (locationRepository.findByName(locationDto.getName()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Location with name '" + locationDto.getName() + "' already exists.");
        }
        Location location = convertToEntity(locationDto);
        Location savedLocation = locationRepository.save(location);
        return new ResponseEntity<>(convertToDto(savedLocation), HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<List<LocationDto>> getAllLocations() {
        List<LocationDto> locations = locationRepository.findAll()
                .stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(locations);
    }

    @GetMapping("/{id}")
    public ResponseEntity<LocationDto> getLocationById(@PathVariable Long id) {
        return locationRepository.findById(id)
                .map(location -> ResponseEntity.ok(convertToDto(location)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<LocationDto> updateLocationById(@PathVariable Long id, @RequestBody LocationDto locationDto) {
        Optional<Location> existingLocationOptional = locationRepository.findById(id);
        if (existingLocationOptional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Location existingLocation = existingLocationOptional.get();

        // Check for name conflict only if the name is being changed
        if (!existingLocation.getName().equals(locationDto.getName())) {
            Optional<Location> conflictingLocation = locationRepository.findByName(locationDto.getName());
            // If a location with the new name exists, and it's not the same location we are updating
            if (conflictingLocation.isPresent() && !conflictingLocation.get().getId().equals(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Another location with name '" + locationDto.getName() + "' already exists.");
            }
        }

        existingLocation.setName(locationDto.getName());
        existingLocation.setPhysicalPath(locationDto.getPhysicalPath());
        // ID should not be changed from DTO for an update operation on a specific ID path
        // The ID from the path variable is authoritative for which entity to update.

        Location updatedLocation = locationRepository.save(existingLocation);
        return ResponseEntity.ok(convertToDto(updatedLocation));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLocationById(@PathVariable Long id) {
        if (locationRepository.existsById(id)) {
            locationRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
