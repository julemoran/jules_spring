package com.julemoran.smooth_web.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.PostConstruct;

@RestController
@RequestMapping("/api/v1/config")
@EnableConfigurationProperties(OidcConfigProperties.class)
@Slf4j
public class OidcConfigController {

    private final OidcConfigProperties oidcConfigProperties;
    private FrontendOidcConfig frontendOidcConfig;

    @Autowired
    public OidcConfigController(OidcConfigProperties oidcConfigProperties) {
        this.oidcConfigProperties = oidcConfigProperties;
    }

    @PostConstruct
    public void init() {
        // Properties are now directly injected and merged by Spring Boot
        this.frontendOidcConfig = new FrontendOidcConfig(
                oidcConfigProperties.getClientId(),
                oidcConfigProperties.getIssuerUri(),
                oidcConfigProperties.getScope(),
                oidcConfigProperties.getRedirectUri()
        );

        log.info("Effective OIDC config for frontend (via Spring properties): ClientId='{}', IssuerUri='{}', Scope='{}', RedirectUri='{}'",
            this.frontendOidcConfig.getClientId(), this.frontendOidcConfig.getIssuerUri(),
            this.frontendOidcConfig.getScope(), this.frontendOidcConfig.getRedirectUri());

        // Validation is handled by @Validated on OidcConfigProperties
        // Spring Boot will prevent startup if @NotBlank constraints are violated and no valid values are bound.
        if (this.frontendOidcConfig.getClientId() == null || this.frontendOidcConfig.getClientId().isBlank() ||
            this.frontendOidcConfig.getIssuerUri() == null || this.frontendOidcConfig.getIssuerUri().isBlank()) {
            // This log helps if properties are actively set to blank, which @NotBlank might not catch if default value is blank.
            // However, with @NotBlank, null or default empty string (if not initialized) will cause binding failure.
             log.warn("OIDC client-id and/or issuer-uri might be missing or blank. Ensure they are set in application.properties or an imported configuration like deployment_parameters.yaml.");
             // If @Validated and @NotBlank are working as expected on OidcConfigProperties,
             // the application context would fail to load if these are truly missing.
             // This check is more of a safeguard or for cases where they might be explicitly set to blank strings.
             if (oidcConfigProperties.getClientId() == null || oidcConfigProperties.getIssuerUri() == null){
                 // This state implies issues with property binding that @Validated should have caught.
                 // For safety, throwing an exception here to prevent running with invalid config.
                 throw new IllegalStateException("Critical OIDC configuration (client-id or issuer-uri) is missing. Check property sources.");
             }
        }
    }

    @GetMapping("/oidc")
    public ResponseEntity<FrontendOidcConfig> getOidcConfiguration() {
         // The PostConstruct validation should ensure properties are loaded,
         // otherwise the application context fails to start.
        return ResponseEntity.ok(frontendOidcConfig);
    }

    /**
     * DTO for exposing OIDC configuration to the frontend.
     */
    @Getter
    private static class FrontendOidcConfig {
        private final String clientId;
        private final String issuerUri;
        private final String scope;
        private final String redirectUri;

        public FrontendOidcConfig(String clientId, String issuerUri, String scope, String redirectUri) {
            this.clientId = clientId;
            this.issuerUri = issuerUri;
            this.scope = scope;
            this.redirectUri = redirectUri;
        }
    }
}
