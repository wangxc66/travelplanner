package com.laioffer.travelplanner.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitFilterTest {

    @Test
    void rejectsAuthenticationAttemptsBeyondTheBoundary() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuthRateLimitFilter filter = new AuthRateLimitFilter(2, 60,
                Clock.fixed(Instant.ofEpochSecond(1_000), ZoneOffset.UTC),
                registry.counter("travelplanner.auth.rate_limit.rejected"));

        assertThat(attempt(filter).getStatus()).isEqualTo(200);
        assertThat(attempt(filter).getStatus()).isEqualTo(200);
        MockHttpServletResponse rejected = attempt(filter);
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
        assertThat(rejected.getContentAsString()).contains("error.rateLimited");
        assertThat(registry.get("travelplanner.auth.rate_limit.rejected").counter().count()).isEqualTo(1);
    }

    private static MockHttpServletResponse attempt(AuthRateLimitFilter filter) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, new MockFilterChain());
        return response;
    }
}
