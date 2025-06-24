package com.julemoran.smooth_web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
// Provide minimal OIDC config to satisfy @NotBlank validation during context loading for this test
@TestPropertySource(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test",
    "oidc.client-id=test-client-for-main-context",
    "oidc.issuer-uri=https://test-issuer.com/for-main-context"
})
class SmoothWebApplicationTests {

	@Test
	void contextLoads() {
	}

}
