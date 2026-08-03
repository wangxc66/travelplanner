package com.laioffer.travelplanner.web;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An error the caller is meant to read.
 *
 * <p>Carries a semantic {@code code} plus parameters alongside an English {@code message}. The client
 * renders the code in the user's language and falls back to the message if it does not recognise the
 * code — so a missing translation degrades to English rather than to a blank alert.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> params;

    public ApiException(HttpStatus status, String code, String message, Map<String, String> params) {
        super(message);
        this.status = status;
        this.code = code;
        this.params = params == null ? Map.of() : params;
    }

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException badRequest(String code, String message, Object... keyValues) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, params(keyValues));
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException conflict(String code, String message, Object... keyValues) {
        return new ApiException(HttpStatus.CONFLICT, code, message, params(keyValues));
    }

    /** @param keyValues alternating key, value pairs */
    private static Map<String, String> params(Object... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), String.valueOf(keyValues[i + 1]));
        }
        return map;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> getParams() {
        return params;
    }
}
