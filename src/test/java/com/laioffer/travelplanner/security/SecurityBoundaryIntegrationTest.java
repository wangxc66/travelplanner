package com.laioffer.travelplanner.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import com.laioffer.travelplanner.repository.CityRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "travelplanner.h2.tcp.enabled=false",
        "travelplanner.osrm.enabled=false"
})
class SecurityBoundaryIntegrationTest {
    @LocalServerPort private int port;
    @Value("${travelplanner.jwt.secret}") private String secret;
    @Autowired private CityRepository cityRepository;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void protectedResourceRejectsMissingMalformedAndExpiredTokens() throws Exception {
        assertUnauthorized(null);
        assertUnauthorized("not-a-jwt");
        assertUnauthorized(expiredToken());
    }

    @Test
    void registrationAcceptsCompliantPasswordAndExplainsShortPassword() throws Exception {
        String username = "user_" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> valid = register(username, "correct-horse-battery-staple");
        assertThat(valid.statusCode()).isEqualTo(200);
        assertThat(valid.body()).contains("\"token\"").contains(username);

        HttpResponse<String> invalid = register("short_" + username, "short");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("error.passwordRules");
    }

    @Test
    void tokenReturnedByRegistrationAuthorizesTripCreation() throws Exception {
        String username = "planner_" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> registered = register(username, "correct-horse-battery-staple");
        assertThat(registered.statusCode()).isEqualTo(200);
        String token = registered.body().replaceFirst(".*\\\"token\\\":\\\"([^\\\"]+)\\\".*", "$1");
        Long cityId = cityRepository.findAllByOrderByNameAsc().getFirst().getId();
        String body = "{\"cityId\":" + cityId + ",\"title\":\"Security flow\","
                + "\"startDate\":\"2026-09-01\",\"numDays\":3}";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/trips"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();

        HttpResponse<String> created = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.body()).contains("Security flow");
    }

    @Test
    void craDevelopmentOriginCanSendAuthenticatedRequests() throws Exception {
        HttpRequest preflight = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/trips"))
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();

        HttpResponse<String> response = client.send(preflight, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("http://localhost:3000");
    }

    private HttpResponse<String> register(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password
                + "\",\"displayName\":\"Test User\"}";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertUnauthorized(String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/trips")).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("error.signInRequired");
        assertThat(response.headers().firstValue("X-Correlation-ID")).isPresent();
    }

    private String expiredToken() {
        Instant now = Instant.now();
        return Jwts.builder().subject("owner").issuer("travelplanner-api")
                .audience().add("travelplanner-web").and()
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
