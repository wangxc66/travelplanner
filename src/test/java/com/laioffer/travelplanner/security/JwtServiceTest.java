package com.laioffer.travelplanner.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private static final String SECRET = "test-only-jwt-secret-with-at-least-32-bytes";

    @Test
    void acceptsOnlyAuthenticTokensForTheConfiguredIssuerAndAudience() {
        JwtService service = service(SECRET);
        String token = service.issue("owner");

        assertThat(service.extractUsername(token)).isEqualTo("owner");
        assertThat(service.extractUsername(token + "tampered")).isNull();
        assertThat(service("another-test-only-secret-with-32-plus-bytes").extractUsername(token)).isNull();
    }

    @Test
    void rejectsExpiredTokens() {
        Instant now = Instant.now();
        String expired = Jwts.builder().subject("owner").issuer("travelplanner-api")
                .audience().add("travelplanner-web").and()
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();

        assertThat(service(SECRET).extractUsername(expired)).isNull();
    }

    @Test
    void rejectsWeakSecretsDemoSecretsInProductionAndLongTtls() {
        assertThatThrownBy(() -> service("short")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(
                "travel-planner-demo-secret-key-please-change-me-1234567890",
                12, "travelplanner-api", "travelplanner-web", false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(SECRET, 72,
                "travelplanner-api", "travelplanner-web", true))
                .isInstanceOf(IllegalStateException.class);
    }

    private static JwtService service(String secret) {
        return new JwtService(secret, 12, "travelplanner-api", "travelplanner-web", true);
    }
}
