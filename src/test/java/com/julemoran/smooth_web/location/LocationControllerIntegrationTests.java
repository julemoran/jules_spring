package com.julemoran.smooth_web.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser; // Added import
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Paths; // Required for Paths.get if used, but not directly here for now
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional // Rollback transactions after each test
// Apply mock user at class level. Specific tests can override if needed (e.g. for anonymous or non-admin user)
@WithMockUser(roles = {"admin"})
public class LocationControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private com.julemoran.smooth_web.scan.ScannedFileRepository scannedFileRepository; // Added

    @Autowired
    private com.julemoran.smooth_web.scan.LocationScanStatusRepository locationScanStatusRepository; // Added

    @Autowired
    private ObjectMapper objectMapper;

    private Location location1;
    private Location location2;

    @BeforeEach
    void setUp() {
        // Clean up database before each test, in correct order
        scannedFileRepository.deleteAll();
        locationScanStatusRepository.deleteAll();
        locationRepository.deleteAll(); // Clean slate

        location1 = new Location(null, "TestLocation1", "/tmp/path1");
        location2 = new Location(null, "TestLocation2", "/tmp/path2");

        // Save directly for setup, then test controller actions
    }

    @Test
    void testAddLocation() throws Exception {
        LocationDto newLocationDto = new LocationDto("NewLoc", "/data/new");

        MvcResult result = mockMvc.perform(post("/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newLocationDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name", is("NewLoc")))
                .andExpect(jsonPath("$.physicalPath", is("/data/new")))
                .andReturn();

        LocationDto createdDto = objectMapper.readValue(result.getResponse().getContentAsString(), LocationDto.class);
        assertThat(locationRepository.findById(createdDto.getId())).isPresent();
    }

    @Test
    void testAddLocation_NameConflict() throws Exception {
        locationRepository.save(location1); // Pre-existing location

        LocationDto conflictDto = new LocationDto(location1.getName(), "/data/conflict");

        mockMvc.perform(post("/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflictDto)))
                .andExpect(status().isConflict());
    }

    @Test
    void testGetAllLocations() throws Exception {
        locationRepository.save(location1);
        locationRepository.save(location2);

        mockMvc.perform(get("/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].name").value(location1.getName())) // Order might vary, better to check specific IDs if possible
                .andExpect(jsonPath("$[1].id").exists())
                .andExpect(jsonPath("$[1].name").value(location2.getName()));
    }

    @Test
    void testGetLocationById() throws Exception {
        Location savedLoc = locationRepository.save(location1);

        mockMvc.perform(get("/locations/" + savedLoc.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(savedLoc.getId().intValue())))
                .andExpect(jsonPath("$.name", is(savedLoc.getName())))
                .andExpect(jsonPath("$.physicalPath", is(savedLoc.getPhysicalPath())));
    }

    @Test
    void testGetLocationById_NotFound() throws Exception {
        mockMvc.perform(get("/locations/9999")) // Non-existent ID
                .andExpect(status().isNotFound());
    }

    @Test
    void testUpdateLocation() throws Exception {
        Location savedLoc = locationRepository.save(location1);
        LocationDto updateDto = new LocationDto(savedLoc.getId(), "UpdatedName", "/updated/path");

        mockMvc.perform(put("/locations/" + savedLoc.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(savedLoc.getId().intValue())))
                .andExpect(jsonPath("$.name", is("UpdatedName")))
                .andExpect(jsonPath("$.physicalPath", is("/updated/path")));

        Location updatedLoc = locationRepository.findById(savedLoc.getId()).orElseThrow();
        assertThat(updatedLoc.getName()).isEqualTo("UpdatedName");
        assertThat(updatedLoc.getPhysicalPath()).isEqualTo("/updated/path");
    }

    @Test
    void testUpdateLocation_NameConflict() throws Exception {
        Location locA = locationRepository.save(new Location(null, "NameA", "/pathA"));
        Location locB = locationRepository.save(new Location(null, "NameB", "/pathB")); // Existing other location

        // Try to update locA to have the name of locB
        LocationDto conflictUpdateDto = new LocationDto(locA.getId(), locB.getName(), locA.getPhysicalPath());

        mockMvc.perform(put("/locations/" + locA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflictUpdateDto)))
                .andExpect(status().isConflict());
    }

    @Test
    void testUpdateLocation_RenameToOwnNameIsAllowed() throws Exception {
        Location savedLoc = locationRepository.save(location1);
        // Update DTO with the same name but different path
        LocationDto updateDto = new LocationDto(savedLoc.getId(), savedLoc.getName(), "/new/path/for/own/name");

        mockMvc.perform(put("/locations/" + savedLoc.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.physicalPath", is("/new/path/for/own/name")));
    }


    @Test
    void testUpdateLocation_NotFound() throws Exception {
        LocationDto updateDto = new LocationDto(9999L, "NonExistent", "/dev/null");
        mockMvc.perform(put("/locations/9999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto)))
                .andExpect(status().isNotFound());
    }

    @Test
    void testDeleteLocationById() throws Exception {
        Location savedLoc = locationRepository.save(location1);

        mockMvc.perform(delete("/locations/" + savedLoc.getId()))
                .andExpect(status().isNoContent());

        assertThat(locationRepository.findById(savedLoc.getId())).isEmpty();
    }

    @Test
    void testDeleteLocationById_NotFound() throws Exception {
        mockMvc.perform(delete("/locations/9999"))
                .andExpect(status().isNotFound());
    }
}
