package com.laioffer.travelplanner.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {

    @Test
    void bcryptUsesCostTwelve() {
        String encoded = new SecurityConfig().passwordEncoder().encode("a secure test password");
        assertThat(encoded).startsWith("$2a$12$");
        assertThat(new BCryptPasswordEncoder().matches("a secure test password", encoded)).isTrue();
    }

    @Test
    void corsAllowsOnlyConfiguredExactOrigins() {
        var source = new SecurityConfig().corsConfigurationSource(
                "https://planner.example.com,https://admin.example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cities");
        var config = source.getCorsConfiguration(request);

        assertThat(config.getAllowedOrigins())
                .containsExactly("https://planner.example.com", "https://admin.example.com");
        assertThat(config.getAllowedOriginPatterns()).isNullOrEmpty();
        assertThat(config.getAllowedHeaders())
                .containsExactly("Authorization", "Content-Type", "X-Correlation-ID");
        assertThat(config.getAllowCredentials()).isFalse();
    }

    @Test
    void corsRejectsWildcardConfiguration() {
        assertThatThrownBy(() -> new SecurityConfig().corsConfigurationSource("https://*.example.com"))
                .isInstanceOf(IllegalStateException.class);
    }
}
