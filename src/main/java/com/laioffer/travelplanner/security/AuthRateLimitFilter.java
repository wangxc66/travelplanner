package com.laioffer.travelplanner.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/** Per-instance defense in depth; clusters must also enforce this boundary at the gateway. */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final int MAX_TRACKED_CLIENTS = 10_000;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int requestsPerWindow;
    private final long windowSeconds;
    private final Clock clock;
    private final Counter rejected;

    @Autowired
    public AuthRateLimitFilter(
            @Value("${travelplanner.security.auth-rate-limit.requests:10}") int requestsPerWindow,
            @Value("${travelplanner.security.auth-rate-limit.window-seconds:60}") long windowSeconds,
            MeterRegistry registry) {
        this(requestsPerWindow, windowSeconds, Clock.systemUTC(),
                registry.counter("travelplanner.auth.rate_limit.rejected"));
    }

    AuthRateLimitFilter(int requestsPerWindow, long windowSeconds, Clock clock, Counter rejected) {
        if (requestsPerWindow < 1 || windowSeconds < 1) {
            throw new IllegalStateException("Authentication rate-limit values must be positive");
        }
        this.requestsPerWindow = requestsPerWindow;
        this.windowSeconds = windowSeconds;
        this.clock = clock;
        this.rejected = rejected;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !("/auth/login".equals(request.getRequestURI())
                || "/auth/register".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long now = clock.instant().getEpochSecond();
        if (windows.size() >= MAX_TRACKED_CLIENTS) {
            windows.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        }
        String key = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        if (windows.size() >= MAX_TRACKED_CLIENTS && !windows.containsKey(key)) {
            key = "overflow"; // hard memory bound during a source-address flood
        }
        Window window = windows.compute(key, (ignored, current) ->
                current == null || current.expiresAt <= now
                        ? new Window(now + windowSeconds, 1) : current.increment());
        if (window.count > requestsPerWindow) {
            rejected.increment();
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(Math.max(1, window.expiresAt - now)));
            response.getWriter().write("{\"message\":\"Too many authentication attempts\","
                    + "\"code\":\"error.rateLimited\",\"params\":{}}");
            return;
        }
        chain.doFilter(request, response);
    }

    private record Window(long expiresAt, int count) {
        Window increment() { return new Window(expiresAt, count + 1); }
    }
}
