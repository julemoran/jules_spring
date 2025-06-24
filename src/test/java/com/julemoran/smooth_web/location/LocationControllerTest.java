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
import static org.mockito.ArgumentMatchers.anyString;
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
    private LocationDto locationDto1;
    private Location location2;
    private LocationDto locationDto2;

    @BeforeEach
    void setUp() {
        location1 = new Location(1L, "test-location-1", "/path/to/loc1");
        locationDto1 = new LocationDto("test-location-1", "/path/to/loc1");

        location2 = new Location(2L, "test-location-2", "/path/to/loc2");
        locationDto2 = new LocationDto("test-location-2", "/path/to/loc2");
    }

    // --- Add Location Tests ---
    @Test
    @WithMockUser(roles = "admin")
    void addLocation_asAdmin_shouldCreateLocation_whenNameIsUnique() throws Exception {
        given(locationRepository.findByName(locationDto1.getName())).willReturn(Optional.empty());
        given(locationRepository.save(any(Location.class))).willReturn(location1);

        mockMvc.perform(post("/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(locationDto1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(locationDto1.getName()))
                .andExpect(jsonPath("$.physicalPath").value(locationDto1.getPhysicalPath()));
    }

    @Test
    @WithMockUser(roles = "admin")
    void addLocation_asAdmin_shouldReturnConflict_whenNameExists() throws Exception {
        given(locationRepository.findByName(locationDto1.getName())).willReturn(Optional.of(location1));

        mockMvc.perform(post("/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(locationDto1)))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "user") // Non-admin user
    void addLocation_asUser_shouldReturnForbidden() throws Exception {
        mockMvc.perform(post("/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(locationDto1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void addLocation_asAnonymous_shouldReturnUnauthorized() throws Exception {
        mockMvc.perform(post("/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(locationDto1)))
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
                .andExpect(jsonPath("$[0].name").value(location1.getName()))
                .andExpect(jsonPath("$[1].name").value(location2.getName()));
    }

    @Test
    @WithMockUser(roles="user") // Should be accessible to any authenticated user
    void getAllLocations_asUser_shouldReturnListOfLocations() throws Exception {
        given(locationRepository.findAll()).willReturn(Arrays.asList(location1, location2));
         mockMvc.perform(get("/locations"))
                .andExpect(status().isOk());
    }


    // --- Get Location By Name Tests ---
    @Test
    @WithAnonymousUser // Should be accessible to anonymous
    void getLocationByName_asAnonymous_shouldReturnLocation_whenExists() throws Exception {
        given(locationRepository.findByName(location1.getName())).willReturn(Optional.of(location1));

        mockMvc.perform(get("/locations/{name}", location1.getName()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(location1.getName()))
                .andExpect(jsonPath("$.physicalPath").value(location1.getPhysicalPath()));
    }

    @Test
    @WithMockUser(roles="user") // Should be accessible to any authenticated user
    void getLocationByName_asUser_shouldReturnLocation_whenExists() throws Exception {
        given(locationRepository.findByName(location1.getName())).willReturn(Optional.of(location1));
         mockMvc.perform(get("/locations/{name}", location1.getName()))
                .andExpect(status().isOk());
    }

    // --- Update Location Tests ---
    @Test
    @WithMockUser(roles = "admin")
    void updateLocation_asAdmin_shouldUpdateLocation_whenExists() throws Exception {
        LocationDto updatedDto = new LocationDto("updated-name", "/updated/path");
        Location updatedEntity = new Location(1L, "updated-name", "/updated/path");

        given(locationRepository.findByName(location1.getName())).willReturn(Optional.of(location1));
        given(locationRepository.findByName(updatedDto.getName())).willReturn(Optional.empty()); // New name is unique
        given(locationRepository.save(any(Location.class))).willReturn(updatedEntity);

        mockMvc.perform(put("/locations/{name}", location1.getName())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(updatedDto.getName()))
                .andExpect(jsonPath("$.physicalPath").value(updatedDto.getPhysicalPath()));
    }

    @Test
    @WithMockUser(roles = "admin")
    void updateLocation_asAdmin_shouldReturnConflict_whenNewNameExists() throws Exception {
        LocationDto updatedDto = new LocationDto(location2.getName(), "/updated/path");

        given(locationRepository.findByName(location1.getName())).willReturn(Optional.of(location1));
        given(locationRepository.findByName(location2.getName())).willReturn(Optional.of(location2));

        mockMvc.perform(put("/locations/{name}", location1.getName())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedDto)))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "user")
    void updateLocation_asUser_shouldReturnForbidden() throws Exception {
        LocationDto updatedDto = new LocationDto("updated-name", "/updated/path");
        mockMvc.perform(put("/locations/{name}", location1.getName())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedDto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void updateLocation_asAnonymous_shouldReturnUnauthorized() throws Exception {
        LocationDto updatedDto = new LocationDto("updated-name", "/updated/path");
        mockMvc.perform(put("/locations/{name}", location1.getName())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedDto)))
                .andExpect(status().isUnauthorized());
    }

    // --- Delete Location Tests ---
    @Test
    @WithMockUser(roles = "admin")
    void deleteLocationByName_asAdmin_shouldDeleteLocation_whenExists() throws Exception {
        given(locationRepository.findByName(location1.getName())).willReturn(Optional.of(location1));
        doNothing().when(locationRepository).deleteByName(location1.getName());

        mockMvc.perform(delete("/locations/{name}", location1.getName()))
                .andExpect(status().isNoContent());
        verify(locationRepository).deleteByName(location1.getName());
    }

    @Test
    @WithMockUser(roles = "user")
    void deleteLocationByName_asUser_shouldReturnForbidden() throws Exception {
        mockMvc.perform(delete("/locations/{name}", location1.getName()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void deleteLocationByName_asAnonymous_shouldReturnUnauthorized() throws Exception {
        mockMvc.perform(delete("/locations/{name}", location1.getName()))
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
    void getLocationByName_asAnonymous_shouldReturnNotFound_whenNotExists() throws Exception {
        given(locationRepository.findByName("non-existent")).willReturn(Optional.empty());
        mockMvc.perform(get("/locations/{name}", "non-existent"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "admin")
    void updateLocation_asAdmin_shouldReturnNotFound_whenNotExists() throws Exception {
        LocationDto updatedDto = new LocationDto("updated-name", "/updated/path");
        given(locationRepository.findByName("non-existent")).willReturn(Optional.empty());

        mockMvc.perform(put("/locations/{name}", "non-existent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedDto)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "admin")
    void deleteLocationByName_asAdmin_shouldReturnNotFound_whenNotExists() throws Exception {
        given(locationRepository.findByName("non-existent")).willReturn(Optional.empty());

        mockMvc.perform(delete("/locations/{name}", "non-existent"))
                .andExpect(status().isNotFound());
    }
}
