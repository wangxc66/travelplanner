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
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        String username = request.username().trim().toLowerCase();
        if (userRepository.existsByUsername(username)) {
            throw ApiException.conflict("error.usernameTaken", "Username already taken");
        }
        String displayName = request.displayName() == null || request.displayName().isBlank()
                ? request.username().trim()
                : request.displayName().trim();
        UserEntity saved = userRepository.save(new UserEntity(
                username, passwordEncoder.encode(request.password()), displayName));
        return new AuthResponse(jwtService.issue(saved.getUsername()), saved.getUsername(), saved.getDisplayName());
    }

    public AuthResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByUsername(request.username().trim().toLowerCase())
                .orElseThrow(() -> ApiException.badRequest("error.badCredentials", "Wrong username or password"));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw ApiException.badRequest("error.badCredentials", "Wrong username or password");
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
}
