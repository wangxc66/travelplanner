package com.laioffer.travelplanner.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All wire contracts in one place — the frontend reads exactly these shapes. */
public final class Dtos {

    private Dtos() {
    }

    /**
     * A user-facing message as a semantic code plus its parameters, never as a finished sentence.
     *
     * <p>The server knows a stop would run past closing time; it has no business deciding in which
     * language, or with what wording, the traveler hears about it. Keeping copy on the client is what
     * lets the whole product switch language without a redeploy — and it means one place to fix wording.
     */
    public record NoticeDto(String code, Map<String, String> params) {

        /** @param keyValues alternating key, value pairs, e.g. {@code of("warning.x", "wait", "30")} */
        public static NoticeDto of(String code, Object... keyValues) {
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                params.put(String.valueOf(keyValues[i]), String.valueOf(keyValues[i + 1]));
            }
            return new NoticeDto(code, params);
        }
    }

    // ---------- auth ----------

    public record RegisterRequest(
            @NotBlank String username,
            @NotBlank String password,
            String displayName) {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {
    }

    public record AuthResponse(String token, String username, String displayName) {
    }

    // ---------- catalog ----------

    public record CityDto(Long id, String name, String country, double lat, double lng,
                          int defaultZoom, String heroEmoji, long poiCount) {
    }

    /**
     * {@code openLabel} is a language-neutral clock range like {@code "09:00 – 18:00"}, or null when
     * {@code alwaysOpen} — the client supplies the wording for that case.
     */
    public record PoiDto(Long id, String name, String category, double lat, double lng,
                         double rating, int avgVisitMinutes, String openLabel, boolean alwaysOpen,
                         String description) {
    }

    // ---------- trips ----------

    public record CreateTripRequest(
            @NotNull Long cityId,
            String title,
            LocalDate startDate,
            @Min(1) @Max(15) int numDays) {
    }

    public record UpdateTripRequest(String title, LocalDate startDate,
                                    Integer numDays, Integer dayStartHour, String defaultMode) {
    }

    public record AddItemRequest(@NotNull Long poiId, @Min(1) int dayIndex) {
    }

    public record ReorderRequest(@NotNull List<Long> itemIds) {
    }

    public record MoveItemRequest(@Min(1) int dayIndex, Integer seq) {
    }

    /**
     * A visit slot on a day, with the leg that got us there. {@code polylineFromPrev} is the
     * Google-encoded geometry of that leg; when it is null the client draws a straight line, which is
     * what happens with the offline estimator.
     */
    public record ItemDto(Long id, PoiDto poi, int seq, boolean locked,
                          String arriveTime, String leaveTime,
                          int travelMinutesFromPrev, double travelKmFromPrev,
                          String polylineFromPrev,
                          List<NoticeDto> warnings) {
    }

    /** {@code date} is ISO-8601; the client formats the weekday in its own locale. */
    public record DayDto(int dayIndex, String date,
                         List<ItemDto> items,
                         int visitMinutes, int travelMinutes, int totalMinutes,
                         String startTime, String endTime,
                         int loadPercent, List<NoticeDto> warnings) {
    }

    /** {@code kind} drives which action button the client offers; {@code code} is what it says. */
    public record SuggestionDto(String kind, String code, Map<String, String> params,
                                Long itemId, Integer fromDay, Integer toDay) {
    }

    public record TripDto(Long id, String title, CityDto city, String startDate, int numDays,
                          int dayStartHour, String defaultMode,
                          List<DayDto> days, List<SuggestionDto> suggestions,
                          int plannedCount) {
    }

    public record TripSummaryDto(Long id, String title, String cityName, String heroEmoji,
                                 String startDate, int numDays, int plannedCount) {
    }
}
