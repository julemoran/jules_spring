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

    @GetMapping("/{name}")
    public ResponseEntity<LocationDto> getLocationByName(@PathVariable String name) {
        Optional<Location> location = locationRepository.findByName(name);
        return location.map(value -> ResponseEntity.ok(convertToDto(value)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{name}")
    public ResponseEntity<LocationDto> updateLocation(@PathVariable String name, @RequestBody LocationDto locationDto) {
        Optional<Location> existingLocationOptional = locationRepository.findByName(name);
        if (existingLocationOptional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Check if the new name in DTO conflicts with another existing location (if name is changed)
        if (!name.equals(locationDto.getName()) && locationRepository.findByName(locationDto.getName()).isPresent()) {
             throw new ResponseStatusException(HttpStatus.CONFLICT, "Another location with name '" + locationDto.getName() + "' already exists.");
        }

        Location existingLocation = existingLocationOptional.get();
        existingLocation.setName(locationDto.getName());
        existingLocation.setPhysicalPath(locationDto.getPhysicalPath());

        Location updatedLocation = locationRepository.save(existingLocation);
        return ResponseEntity.ok(convertToDto(updatedLocation));
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT) // Return 204 No Content on successful deletion
    public ResponseEntity<Void> deleteLocationByName(@PathVariable String name) {
        Optional<Location> location = locationRepository.findByName(name);
        if (location.isPresent()) {
            locationRepository.deleteByName(name); // Assuming deleteByName is transactional or handles not found appropriately
            // It's better if deleteByName returns a count or throws if not found, to be sure.
            // For now, we'll trust it or rely on the findByName check.
            // If deleteByName doesn't exist or doesn't behave as expected, use delete(location.get())
            // For example, if deleteByName is not transactional, it might be better to do:
            // locationRepository.delete(location.get());
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
