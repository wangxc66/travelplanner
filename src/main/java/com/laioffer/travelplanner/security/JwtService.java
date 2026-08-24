package com.laioffer.travelplanner.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;
    private final String issuer;
    private final String audience;

    public JwtService(@Value("${travelplanner.jwt.secret}") String secret,
                      @Value("${travelplanner.jwt.ttl-hours}") long ttlHours,
                      @Value("${travelplanner.jwt.issuer}") String issuer,
                      @Value("${travelplanner.jwt.audience}") String audience,
                      @Value("${travelplanner.jwt.allow-insecure-secret:false}") boolean allowInsecureSecret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT secret must contain at least 32 UTF-8 bytes");
        }
        if (!allowInsecureSecret && secret.startsWith("travel-planner-demo-")) {
            throw new IllegalStateException("The demo JWT secret is forbidden outside local/test profiles");
        }
        if (ttlHours < 1 || ttlHours > 24) {
            throw new IllegalStateException("JWT TTL must be between 1 and 24 hours");
        }
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalStateException("JWT issuer and audience are required");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofHours(ttlHours);
        this.issuer = issuer;
        this.audience = audience;
    }

    public String issue(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .issuer(issuer)
                .audience().add(audience).and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** @return the username, or {@code null} when the token is missing, expired or tampered with. */
    public String extractUsername(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (Exception e) {
            return null;
        }
    }
}
