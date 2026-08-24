package com.laioffer.travelplanner.security;

import com.laioffer.travelplanner.repository.UserRepository;
import com.laioffer.travelplanner.web.CorrelationIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return username -> userRepository.findByUsername(username)
                .map(u -> User.withUsername(u.getUsername())
                        .password(u.getPassword())
                        .roles("USER")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException(username));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                           AuthRateLimitFilter rateLimitFilter,
                                           CorrelationIdFilter correlationIdFilter,
                                           CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"message\":\"Please sign in\","
                                    + "\"code\":\"error.signInRequired\",\"params\":{}}");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"message\":\"Access denied\","
                                    + "\"code\":\"error.accessDenied\",\"params\":{}}");
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**", "/h2-console/**", "/actuator/health").permitAll()
                        // Browsing the POI catalog needs no account — lowers the barrier to a first plan.
                        .requestMatchers(HttpMethod.GET, "/api/cities/**", "/api/categories/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, CorrelationIdFilter.class)
                .addFilterAfter(jwtAuthFilter, AuthRateLimitFilter.class)
                ;
        return http.build();
    }

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationRegistration(CorrelationIdFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<AuthRateLimitFilter> rateLimitRegistration(AuthRateLimitFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtRegistration(JwtAuthFilter filter) {
        return disabledRegistration(filter);
    }

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> disabledRegistration(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${travelplanner.security.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}") String origins) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> allowedOrigins = java.util.Arrays.stream(origins.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (allowedOrigins.isEmpty() || allowedOrigins.stream().anyMatch(value -> value.contains("*"))) {
            throw new IllegalStateException("CORS requires at least one exact origin and forbids wildcards");
        }
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-ID"));
        config.setExposedHeaders(List.of("X-Correlation-ID"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
