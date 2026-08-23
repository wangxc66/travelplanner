package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.dto.Dtos.AuthResponse;
import com.laioffer.travelplanner.dto.Dtos.LoginRequest;
import com.laioffer.travelplanner.dto.Dtos.RegisterRequest;
import com.laioffer.travelplanner.entity.UserEntity;
import com.laioffer.travelplanner.repository.UserRepository;
import com.laioffer.travelplanner.security.JwtService;
import com.laioffer.travelplanner.web.ApiException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyPasswordHash = passwordEncoder.encode("timing-only-password-value");
    }

    public AuthResponse register(RegisterRequest request) {
        String username = normalizeUsername(request.username());
        requirePassword(request.password());
        if (userRepository.existsByUsername(username)) {
            throw ApiException.conflict("error.usernameTaken", "Username already taken");
        }
        String displayName = request.displayName() == null || request.displayName().isBlank()
                ? request.username().trim()
                : request.displayName().trim();
        UserEntity saved;
        try {
            saved = userRepository.save(new UserEntity(
                    username, passwordEncoder.encode(request.password()), displayName));
        } catch (DataIntegrityViolationException conflict) {
            // The database unique constraint closes the concurrent registration race.
            throw ApiException.conflict("error.usernameTaken", "Username already taken");
        }
        return new AuthResponse(jwtService.issue(saved.getUsername()), saved.getUsername(), saved.getDisplayName());
    }

    public AuthResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByUsername(normalizeUsername(request.username())).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw ApiException.unauthorized("error.badCredentials", "Wrong username or password");
        }
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw ApiException.unauthorized("error.badCredentials", "Wrong username or password");
        }
        return new AuthResponse(jwtService.issue(user.getUsername()), user.getUsername(), user.getDisplayName());
    }

    /** The authenticated caller, resolved from the JWT that {@code JwtAuthFilter} already validated. */
    public UserEntity currentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof UserDetails details)) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "error.signInRequired", "Please sign in");
        }
        return userRepository.findByUsername(details.getUsername())
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "error.signInRequired", "Please sign in"));
    }

    private static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static void requirePassword(String password) {
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < 12 || bytes > 72) {
            throw ApiException.badRequest("error.passwordRules",
                    "Password must be at least 12 characters and at most 72 UTF-8 bytes");
        }
    }
}
