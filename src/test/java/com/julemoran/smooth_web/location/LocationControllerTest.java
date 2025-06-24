package com.julemoran.smooth_web.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.julemoran.smooth_web.config.SecurityConfig;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@WebMvcTest(LocationController.class)
@Import(SecurityConfig.class) // Import the security configuration
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test"
})
public class LocationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LocationRepository locationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Location location1;
    private LocationDto locationDto1; // DTO for location1, now includes ID
    private Location location2;
    private LocationDto locationDto2; // DTO for location2, now includes ID

    @BeforeEach
    void setUp() {
        location1 = new Location(1L, "test-location-1", "/path/to/loc1");
        // Assuming LocationDto now includes id, which is good for responses
        locationDto1 = new LocationDto(1L, "test-location-1", "/path/to/loc1");

        location2 = new Location(2L, "test-location-2", "/path/to/loc2");
        locationDto2 = new LocationDto(2L, "test-location-2", "/path/to/loc2");
    }

    // --- Add Location Tests ---
    @Test
    @WithMockUser(roles = "admin")
    void addLocation_asAdmin_shouldCreateLocation_whenNameIsUnique() throws Exception {
        // For add, DTO typically doesn't have ID, or it's ignored.
        LocationDto createDto = new LocationDto(null, "test-location-1", "/path/to/loc1");
        given(locationRepository.findByName(createDto.getName())).willReturn(Optional.empty());
        given(locationRepository.save(any(Location.class))).willReturn(location1); // save returns entity with ID

        mockMvc.perform(post("/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(location1.getId())) // Expect ID in response
                .andExpect(jsonPath("$.name").value(createDto.getName()))
                .andExpect(jsonPath("$.physicalPath").value(createDto.getPhysicalPath()));
    }

    @Test
    @WithMockUser(roles = "admin")
    void addLocation_asAdmin_shouldReturnConflict_whenNameExists() throws Exception {
        LocationDto createDto = new LocationDto(null, "test-location-1", "/path/to/loc1");
        given(locationRepository.findByName(createDto.getName())).willReturn(Optional.of(location1));

        mockMvc.perform(post("/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "user") // Non-admin user
    void addLocation_asUser_shouldReturnForbidden() throws Exception {
        LocationDto createDto = new LocationDto(null, "test-location-1", "/path/to/loc1");
        mockMvc.perform(post("/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void addLocation_asAnonymous_shouldReturnUnauthorized() throws Exception {
        LocationDto createDto = new LocationDto(null, "test-location-1", "/path/to/loc1");
        mockMvc.perform(post("/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createDto)))
                .andExpect(status().isUnauthorized());
    }


    // --- Get All Locations Tests ---
    @Test
    @WithAnonymousUser // Should be accessible to anonymous
    void getAllLocations_asAnonymous_shouldReturnListOfLocations() throws Exception {
        given(locationRepository.findAll()).willReturn(Arrays.asList(location1, location2));

        mockMvc.perform(get("/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(location1.getId()))
                .andExpect(jsonPath("$[0].name").value(location1.getName()))
                .andExpect(jsonPath("$[1].id").value(location2.getId()))
                .andExpect(jsonPath("$[1].name").value(location2.getName()));
    }

    @Test
    @WithMockUser(roles="user") // Should be accessible to any authenticated user
    void getAllLocations_asUser_shouldReturnListOfLocations() throws Exception {
        given(locationRepository.findAll()).willReturn(Arrays.asList(location1, location2));
        mockMvc.perform(get("/locations"))
                .andExpect(status().isOk());
    }


    // --- Get Location By ID Tests ---
    @Test
    @WithAnonymousUser // Should be accessible to anonymous
    void getLocationById_asAnonymous_shouldReturnLocation_whenExists() throws Exception {
        given(locationRepository.findById(location1.getId())).willReturn(Optional.of(location1));

        mockMvc.perform(get("/locations/{id}", location1.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(location1.getId()))
                .andExpect(jsonPath("$.name").value(location1.getName()))
                .andExpect(jsonPath("$.physicalPath").value(location1.getPhysicalPath()));
    }

    @Test
    @WithMockUser(roles="user") // Should be accessible to any authenticated user
    void getLocationById_asUser_shouldReturnLocation_whenExists() throws Exception {
        given(locationRepository.findById(location1.getId())).willReturn(Optional.of(location1));
        mockMvc.perform(get("/locations/{id}", location1.getId()))
                .andExpect(status().isOk());
    }

    // --- Update Location Tests ---
    @Test
    @WithMockUser(roles = "admin")
    void updateLocationById_asAdmin_shouldUpdateLocation_whenExists() throws Exception {
        // DTO for update might or might not contain ID. If it does, it's usually ignored by server for path-specified ID.
        // Let's assume DTO does not need ID for update request body, or it can be anything.
        LocationDto updatedReqDto = new LocationDto(null, "updated-name", "/updated/path");
        Location updatedEntity = new Location(location1.getId(), "updated-name", "/updated/path"); // Entity after update

        given(locationRepository.findById(location1.getId())).willReturn(Optional.of(location1));
        given(locationRepository.findByName(updatedReqDto.getName())).willReturn(Optional.empty()); // New name is unique
        given(locationRepository.save(any(Location.class))).willReturn(updatedEntity);

        mockMvc.perform(put("/locations/{id}", location1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedReqDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(location1.getId()))
                .andExpect(jsonPath("$.name").value(updatedReqDto.getName()))
                .andExpect(jsonPath("$.physicalPath").value(updatedReqDto.getPhysicalPath()));
    }

    @Test
    @WithMockUser(roles = "admin")
    void updateLocationById_asAdmin_shouldReturnConflict_whenNewNameExistsForDifferentLocation() throws Exception {
        // DTO for update attempts to use name of location2 for location1
        LocationDto updatedReqDto = new LocationDto(null, location2.getName(), "/updated/path");

        given(locationRepository.findById(location1.getId())).willReturn(Optional.of(location1));
        // mock that a location with the target name (location2's name) exists and it's indeed location2
        given(locationRepository.findByName(location2.getName())).willReturn(Optional.of(location2));

        mockMvc.perform(put("/locations/{id}", location1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedReqDto)))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "user")
    void updateLocationById_asUser_shouldReturnForbidden() throws Exception {
        LocationDto updatedReqDto = new LocationDto(null, "updated-name", "/updated/path");
        mockMvc.perform(put("/locations/{id}", location1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedReqDto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void updateLocationById_asAnonymous_shouldReturnUnauthorized() throws Exception {
        LocationDto updatedReqDto = new LocationDto(null, "updated-name", "/updated/path");
        mockMvc.perform(put("/locations/{id}", location1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedReqDto)))
                .andExpect(status().isUnauthorized());
    }

    // --- Delete Location Tests ---
    @Test
    @WithMockUser(roles = "admin")
    void deleteLocationById_asAdmin_shouldDeleteLocation_whenExists() throws Exception {
        given(locationRepository.existsById(location1.getId())).willReturn(true);
        doNothing().when(locationRepository).deleteById(location1.getId());

        mockMvc.perform(delete("/locations/{id}", location1.getId()))
                .andExpect(status().isNoContent());
        verify(locationRepository).deleteById(location1.getId());
    }

    @Test
    @WithMockUser(roles = "user")
    void deleteLocationById_asUser_shouldReturnForbidden() throws Exception {
        mockMvc.perform(delete("/locations/{id}", location1.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void deleteLocationById_asAnonymous_shouldReturnUnauthorized() throws Exception {
        mockMvc.perform(delete("/locations/{id}", location1.getId()))
                .andExpect(status().isUnauthorized());
    }

    // --- Test existing non-modifying endpoints with new security context ---
    @Test
    @WithAnonymousUser
    void getAllLocations_asAnonymous_shouldReturnEmptyList_whenNoLocations() throws Exception {
        given(locationRepository.findAll()).willReturn(Collections.emptyList());
        mockMvc.perform(get("/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithAnonymousUser
    void getLocationById_asAnonymous_shouldReturnNotFound_whenNotExists() throws Exception {
        given(locationRepository.findById(anyLong())).willReturn(Optional.empty());
        mockMvc.perform(get("/locations/{id}", 999L)) // Use a non-existent ID
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "admin")
    void updateLocationById_asAdmin_shouldReturnNotFound_whenNotExists() throws Exception {
        LocationDto updatedReqDto = new LocationDto(null, "updated-name", "/updated/path");
        given(locationRepository.findById(anyLong())).willReturn(Optional.empty());

        mockMvc.perform(put("/locations/{id}", 999L) // Use a non-existent ID
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedReqDto)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "admin")
    void deleteLocationById_asAdmin_shouldReturnNotFound_whenNotExists() throws Exception {
        given(locationRepository.existsById(anyLong())).willReturn(false);

        mockMvc.perform(delete("/locations/{id}", 999L)) // Use a non-existent ID
                .andExpect(status().isNotFound());
    }
}
