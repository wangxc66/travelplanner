package com.laioffer.travelplanner.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of(
                "message", e.getMessage(),
                "code", e.getCode(),
                "params", e.getParams()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        FieldError field = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String code = validationCode(field);
        String message = validationMessage(field);
        return ResponseEntity.badRequest().body(Map.of(
                "message", message,
                "code", code,
                "params", Map.of()));
    }

    private static String validationCode(FieldError field) {
        if (field == null) {
            return "error.invalidRequest";
        }
        return switch (field.getField()) {
            case "username" -> "error.usernameRules";
            case "password" -> "error.passwordRules";
            case "displayName" -> "error.displayNameRules";
            default -> "error.invalidRequest";
        };
    }

    private static String validationMessage(FieldError field) {
        return switch (validationCode(field)) {
            case "error.usernameRules" ->
                    "Username must be 3–64 letters, numbers, dots, dashes, or underscores";
            case "error.passwordRules" ->
                    "Password must be at least 12 characters and at most 72 UTF-8 bytes";
            case "error.displayNameRules" -> "Display name must be 100 characters or fewer";
            default -> field == null ? "Invalid request"
                    : field.getField() + " " + field.getDefaultMessage();
        };
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "message", String.valueOf(e.getMessage()),
                "code", "error.invalidRequest",
                "params", Map.of()));
    }
}
