package com.julemoran.smooth_web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Configuration
@ConfigurationProperties(prefix = "oidc")
@Data
@Validated
public class OidcConfigProperties {

    /**
     * OIDC Client ID for the frontend application.
     */
    @NotBlank(message = "OIDC Client ID must be set")
    private String clientId;

    /**
     * OIDC Issuer URI for the frontend application.
     */
    @NotBlank(message = "OIDC Issuer URI must be set")
    private String issuerUri;

    /**
     * Scopes to request during OIDC authentication. Defaults to "openid profile email".
     */
    private String scope = "openid profile email";

    /**
     * Frontend redirect URI for OIDC authentication. Defaults to "/auth/callback".
     * This is the path within the Vue app where the OIDC provider should redirect after authentication.
     */
    private String redirectUri = "/auth/callback"; // Relative to the Vue app's base URL

}
