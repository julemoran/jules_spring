package com.julemoran.smooth_web.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "oidc.client-id=default-test-client-id",
        "oidc.issuer-uri=https://default-test-issuer.com",
        "oidc.scope=openid profile email default",
        "oidc.redirect-uri=/auth/callback-default"
})
public class OidcConfigControllerOverrideIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // @BeforeEach and @AfterEach related to deploymentParamsPath removed as those tests are gone.

    @Test
    void getOidcConfiguration_shouldReturnPropertiesFromTestPropertySource() throws Exception {
        mockMvc.perform(get("/api/v1/config/oidc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId", is("default-test-client-id")))
                .andExpect(jsonPath("$.issuerUri", is("https://default-test-issuer.com")))
                .andExpect(jsonPath("$.scope", is("openid profile email default")))
                .andExpect(jsonPath("$.redirectUri", is("/auth/callback-default")));
    }
}
