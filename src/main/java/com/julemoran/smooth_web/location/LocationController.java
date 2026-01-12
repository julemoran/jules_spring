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

    @GetMapping("/{id}") // Changed from {name} to {id}
    public ResponseEntity<LocationDto> getLocationById(@PathVariable Long id) { // Parameter changed to Long id
        Optional<Location> location = locationRepository.findById(id); // Use findById
        return location.map(value -> ResponseEntity.ok(convertToDto(value)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}") // Changed from {name} to {id}
    public ResponseEntity<LocationDto> updateLocation(@PathVariable Long id, @RequestBody LocationDto locationDto) { // Parameter changed to Long id
        Optional<Location> existingLocationOptional = locationRepository.findById(id); // Use findById
        if (existingLocationOptional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Location existingLocation = existingLocationOptional.get();

        // Check if the new name in DTO conflicts with another existing location (if name is changed)
        // And the conflicting location is not the current one being updated
        if (!existingLocation.getName().equals(locationDto.getName())) {
            Optional<Location> conflictingLocation = locationRepository.findByName(locationDto.getName());
            if (conflictingLocation.isPresent() && !conflictingLocation.get().getId().equals(id)) {
                 throw new ResponseStatusException(HttpStatus.CONFLICT, "Another location with name '" + locationDto.getName() + "' already exists.");
            }
        }

        existingLocation.setName(locationDto.getName());
        existingLocation.setPhysicalPath(locationDto.getPhysicalPath());

        Location updatedLocation = locationRepository.save(existingLocation);
        return ResponseEntity.ok(convertToDto(updatedLocation));
    }

    @DeleteMapping("/{id}") // Changed from {name} to {id}
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteLocationById(@PathVariable Long id) { // Parameter changed to Long id
        if (locationRepository.existsById(id)) { // Check if location exists
            locationRepository.deleteById(id); // Use deleteById
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
