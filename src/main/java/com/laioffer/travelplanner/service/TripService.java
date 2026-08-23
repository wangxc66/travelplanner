package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.dto.Dtos.AddItemRequest;
import com.laioffer.travelplanner.dto.Dtos.CreateTripRequest;
import com.laioffer.travelplanner.dto.Dtos.DayDto;
import com.laioffer.travelplanner.dto.Dtos.ItemDto;
import com.laioffer.travelplanner.dto.Dtos.MoveItemRequest;
import com.laioffer.travelplanner.dto.Dtos.NoticeDto;
import com.laioffer.travelplanner.dto.Dtos.SuggestionDto;
import com.laioffer.travelplanner.dto.Dtos.TripDto;
import com.laioffer.travelplanner.dto.Dtos.TripSummaryDto;
import com.laioffer.travelplanner.dto.Dtos.UpdateTripRequest;
import com.laioffer.travelplanner.entity.City;
import com.laioffer.travelplanner.entity.ItineraryItem;
import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.entity.TravelMode;
import com.laioffer.travelplanner.entity.Trip;
import com.laioffer.travelplanner.entity.UserEntity;
import com.laioffer.travelplanner.repository.CityRepository;
import com.laioffer.travelplanner.repository.ItineraryItemRepository;
import com.laioffer.travelplanner.repository.PoiRepository;
import com.laioffer.travelplanner.repository.TripRepository;
import com.laioffer.travelplanner.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TripService {

    private static final int MAX_DAYS = 15;
    private static final int TEMP_SEQUENCE_BASE = 1_000_000_000;

    private final TripRepository tripRepository;
    private final ItineraryItemRepository itemRepository;
    private final CityRepository cityRepository;
    private final PoiRepository poiRepository;
    private final RoutePlanner routePlanner;
    private final int defaultDayStartHour;
    private final int dayEndHour;

    public TripService(TripRepository tripRepository, ItineraryItemRepository itemRepository,
                       CityRepository cityRepository, PoiRepository poiRepository,
                       RoutePlanner routePlanner,
                       @org.springframework.beans.factory.annotation.Value("${travelplanner.planner.day-start-hour}") int defaultDayStartHour,
                       @org.springframework.beans.factory.annotation.Value("${travelplanner.planner.day-end-hour}") int dayEndHour) {
        this.tripRepository = tripRepository;
        this.itemRepository = itemRepository;
        this.cityRepository = cityRepository;
        this.poiRepository = poiRepository;
        this.routePlanner = routePlanner;
        this.defaultDayStartHour = defaultDayStartHour;
        this.dayEndHour = dayEndHour;
    }

    // ------------------------------------------------------------------ trips

    @Transactional
    public TripDto create(UserEntity user, CreateTripRequest request) {
        if (request.numDays() < 1 || request.numDays() > MAX_DAYS) {
            throw ApiException.badRequest("error.tripDaysRange", "A trip must be between 1 and " + MAX_DAYS + " days", "max", MAX_DAYS);
        }
        City city = cityRepository.findById(request.cityId())
                .orElseThrow(() -> ApiException.notFound("error.cityNotFound", "City not found"));
        String title = request.title() == null || request.title().isBlank()
                ? request.numDays() + " days in " + city.getName()
                : request.title().trim();
        LocalDate start = request.startDate() == null ? LocalDate.now().plusDays(14) : request.startDate();
        Trip trip = tripRepository.save(new Trip(user, city, title, start, request.numDays(),
                defaultDayStartHour, TravelMode.TRANSIT));
        return detail(trip);
    }

    @Transactional(readOnly = true)
    public List<TripSummaryDto> list(UserEntity user) {
        return tripRepository.findSummariesByUserId(user.getId()).stream()
                .map(t -> new TripSummaryDto(t.getId(), t.getTitle(), t.getCityName(),
                        t.getHeroEmoji(), String.valueOf(t.getStartDate()), t.getNumDays(),
                        Math.toIntExact(t.getItemCount())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TripDto get(UserEntity user, Long tripId) {
        return detail(load(user, tripId));
    }

    @Transactional
    public TripDto update(UserEntity user, Long tripId, UpdateTripRequest request) {
        Trip trip = loadForUpdate(user, tripId);
        if (request.title() != null && !request.title().isBlank()) {
            trip.setTitle(request.title().trim());
        }
        if (request.startDate() != null) {
            trip.setStartDate(request.startDate());
        }
        if (request.dayStartHour() != null) {
            if (request.dayStartHour() < 5 || request.dayStartHour() > 14) {
                throw ApiException.badRequest("error.dayStartHourRange",
                        "Day start hour must be between 5 and 14", "min", 5, "max", 14);
            }
            trip.setDayStartHour(request.dayStartHour());
        }
        if (request.defaultMode() != null) {
            trip.setDefaultMode(parseMode(request.defaultMode()));
        }
        if (request.numDays() != null) {
            int days = request.numDays();
            if (days < 1 || days > MAX_DAYS) {
                throw ApiException.badRequest("error.tripDaysRange", "A trip must be between 1 and " + MAX_DAYS + " days", "max", MAX_DAYS);
            }
            if (days < trip.getNumDays()) {
                // Shrinking the trip must not silently drop stops: fold them into the last day.
                // Include the retained day's existing items in the two-phase rewrite. Assigning
                // orphan sequence numbers directly can collide with rows from another removed day
                // before Hibernate has flushed every update.
                List<ItineraryItem> folded = itemRepository.findByTripIdOrderByDayIndexAscSeqAsc(tripId).stream()
                        .filter(i -> i.getDayIndex() >= days)
                        .toList();
                for (ItineraryItem item : folded) {
                    item.setDayIndex(days);
                }
                persistOrder(folded);
            }
            trip.setNumDays(days);
        }
        tripRepository.save(trip);
        return detail(trip);
    }

    @Transactional
    public void delete(UserEntity user, Long tripId) {
        tripRepository.delete(loadForUpdate(user, tripId));
    }

    // ------------------------------------------------------------------ itinerary items

    @Transactional
    public TripDto addItem(UserEntity user, Long tripId, AddItemRequest request) {
        Trip trip = loadForUpdate(user, tripId);
        int day = requireDay(trip, request.dayIndex());
        Poi poi = poiRepository.findById(request.poiId())
                .orElseThrow(() -> ApiException.notFound("error.poiNotFound", "POI not found"));
        if (!poi.getCity().getId().equals(trip.getCity().getId())) {
            throw ApiException.badRequest("error.poiWrongCity", "That place is not in " + trip.getCity().getName(), "city", trip.getCity().getName());
        }
        if (itemRepository.existsByTripIdAndPoiId(tripId, poi.getId())) {
            throw ApiException.conflict("error.poiAlreadyPlanned", poi.getName() + " is already in this trip", "name", poi.getName());
        }
        int seq = Math.toIntExact(itemRepository.countByTripIdAndDayIndex(tripId, day));
        itemRepository.save(new ItineraryItem(trip, poi, day, seq));
        return detail(trip);
    }

    @Transactional
    public TripDto removeItem(UserEntity user, Long tripId, Long itemId) {
        Trip trip = loadForUpdate(user, tripId);
        ItineraryItem item = item(trip, itemId);
        int day = item.getDayIndex();
        itemRepository.delete(item);
        itemRepository.flush();
        resequence(tripId, day);
        return detail(trip);
    }

    /** Drag-and-drop inside one day: the client sends the full new order of item ids. */
    @Transactional
    public TripDto reorderDay(UserEntity user, Long tripId, int dayIndex, List<Long> itemIds) {
        Trip trip = loadForUpdate(user, tripId);
        int day = requireDay(trip, dayIndex);
        if (itemIds == null) {
            throw ApiException.badRequest("error.reorderMismatch",
                    "Reorder must list exactly the items of day " + day, "day", day);
        }
        Map<Long, ItineraryItem> current = new LinkedHashMap<>();
        for (ItineraryItem item : itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(tripId, day)) {
            current.put(item.getId(), item);
        }
        LinkedHashSet<Long> requested = new LinkedHashSet<>(itemIds);
        if (itemIds.size() != current.size() || requested.size() != itemIds.size()
                || !current.keySet().equals(requested)) {
            throw ApiException.badRequest("error.reorderMismatch", "Reorder must list exactly the items of day " + day, "day", day);
        }
        List<ItineraryItem> ordered = new ArrayList<>(itemIds.size());
        for (Long id : itemIds) {
            ordered.add(current.get(id));
        }
        persistOrder(ordered);
        return detail(trip);
    }

    /** Drag across days, or the one-click "move to day N" from a rebalance suggestion. */
    @Transactional
    public TripDto moveItem(UserEntity user, Long tripId, Long itemId, MoveItemRequest request) {
        Trip trip = loadForUpdate(user, tripId);
        moveItemInternal(trip, itemId, request.dayIndex(), request.seq());
        return detail(trip);
    }

    private void moveItemInternal(Trip trip, Long itemId, int requestedDay, Integer requestedSeq) {
        ItineraryItem item = item(trip, itemId);
        int targetDay = requireDay(trip, requestedDay);
        int sourceDay = item.getDayIndex();

        List<ItineraryItem> target = new ArrayList<>(
                itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(trip.getId(), targetDay));
        target.remove(item);
        if (requestedSeq != null && requestedSeq < 0) {
            throw ApiException.badRequest("error.itemSeqRange",
                    "Item position must not be negative", "seq", requestedSeq);
        }
        int position = requestedSeq == null ? target.size() : Math.min(requestedSeq, target.size());
        target.add(position, item);
        item.setDayIndex(targetDay);
        persistOrder(target);
        if (sourceDay != targetDay) {
            resequence(trip.getId(), sourceDay);
        }
    }

    @Transactional
    public TripDto toggleLock(UserEntity user, Long tripId, Long itemId) {
        Trip trip = loadForUpdate(user, tripId);
        ItineraryItem item = item(trip, itemId);
        item.setLocked(!item.isLocked());
        itemRepository.save(item);
        return detail(trip);
    }

    // ------------------------------------------------------------------ the smart bits

    /**
     * Re-orders one day against the full route and persists it. Locked items remain in their original
     * slots but still contribute their incoming/outgoing travel, visit duration and opening window.
     */
    @Transactional
    public TripDto optimizeDay(UserEntity user, Long tripId, int dayIndex, String modeOverride) {
        Trip trip = loadForUpdate(user, tripId);
        int day = requireDay(trip, dayIndex);
        TravelMode mode = resolveMode(trip, modeOverride);
        optimizeDayInternal(trip, day, mode);
        return detail(trip);
    }

    private void optimizeDayInternal(Trip trip, int day, TravelMode mode) {
        List<ItineraryItem> items = itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(trip.getId(), day);
        if (items.size() < 2) {
            return;
        }

        long movableCount = items.stream().filter(item -> !item.isLocked()).count();
        if (movableCount <= 1) {
            return;
        }

        List<Poi> pois = items.stream().map(ItineraryItem::getPoi).toList();
        List<Boolean> locked = items.stream().map(ItineraryItem::isLocked).toList();
        List<Integer> order = routePlanner.optimizeOrder(pois, mode, trip.getDayStartHour(), locked);
        validateOptimizationOrder(order, locked);

        List<ItineraryItem> reordered = order.stream().map(items::get).toList();
        persistOrder(reordered);
    }

    @Transactional
    public TripDto optimizeAllDays(UserEntity user, Long tripId, String modeOverride) {
        Trip trip = loadForUpdate(user, tripId);
        TravelMode mode = resolveMode(trip, modeOverride);
        for (int day = 1; day <= trip.getNumDays(); day++) {
            optimizeDayInternal(trip, day, mode);
        }
        return detail(trip);
    }

    /**
     * Moves the trailing stop off every over-full day onto the emptiest day that still has room.
     * Returns the updated trip; the caller decides whether to apply or only preview the suggestions.
     */
    @Transactional
    public TripDto rebalance(UserEntity user, Long tripId) {
        Trip trip = loadForUpdate(user, tripId);
        TravelMode mode = trip.getDefaultMode();
        boolean movedAnything = false;

        for (int pass = 0; pass < trip.getNumDays(); pass++) {
            Map<Integer, List<ItineraryItem>> byDay = itemsByDay(trip);
            Integer overloaded = null;
            int worstEnd = 0;
            for (int day = 1; day <= trip.getNumDays(); day++) {
                int end = dayEnd(byDay.get(day), trip, mode);
                if (end > dayEndHour * 60 && end > worstEnd && byDay.get(day).size() > 1) {
                    worstEnd = end;
                    overloaded = day;
                }
            }
            if (overloaded == null) {
                break;
            }
            Integer lightest = null;
            int lightestEnd = Integer.MAX_VALUE;
            for (int day = 1; day <= trip.getNumDays(); day++) {
                if (day == overloaded) {
                    continue;
                }
                int end = dayEnd(byDay.get(day), trip, mode);
                if (end < lightestEnd) {
                    lightestEnd = end;
                    lightest = day;
                }
            }
            if (lightest == null || lightestEnd >= worstEnd) {
                break;
            }
            ItineraryItem candidate = byDay.get(overloaded).reversed().stream()
                    .filter(i -> !i.isLocked())
                    .findFirst()
                    .orElse(null);
            if (candidate == null) {
                break;
            }
            moveItemInternal(trip, candidate.getId(), lightest, null);
            movedAnything = true;
        }

        if (movedAnything) {
            for (int day = 1; day <= trip.getNumDays(); day++) {
                optimizeDayInternal(trip, day, mode);
            }
        }
        return detail(trip);
    }

    // ------------------------------------------------------------------ assembling the response

    private TripDto detail(Trip trip) {
        TravelMode mode = trip.getDefaultMode();
        Map<Integer, List<ItineraryItem>> byDay = itemsByDay(trip);
        List<DayDto> days = new ArrayList<>();
        int planned = 0;
        int dayCapacity = (dayEndHour - trip.getDayStartHour()) * 60;

        for (int day = 1; day <= trip.getNumDays(); day++) {
            List<ItineraryItem> items = byDay.get(day);
            planned += items.size();
            List<Poi> pois = items.stream().map(ItineraryItem::getPoi).toList();
            RoutePlanner.DayPlan plan = routePlanner.buildDay(pois, mode, trip.getDayStartHour(), dayEndHour);

            List<ItemDto> itemDtos = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                ItineraryItem item = items.get(i);
                RoutePlanner.StopPlan stop = plan.stops().get(i);
                itemDtos.add(new ItemDto(item.getId(), CatalogService.toDto(item.getPoi()), i, item.isLocked(),
                        RoutePlanner.fmt(stop.arriveMinutes()), RoutePlanner.fmt(stop.leaveMinutes()),
                        stop.travelMinutesFromPrev(), stop.travelKmFromPrev(), stop.polylineFromPrev(),
                        stop.warnings()));
            }

            int total = plan.visitMinutes() + plan.travelMinutes();
            LocalDate date = trip.getStartDate() == null ? null : trip.getStartDate().plusDays(day - 1L);
            days.add(new DayDto(day,
                    date == null ? null : date.toString(),
                    itemDtos,
                    plan.visitMinutes(), plan.travelMinutes(), total,
                    RoutePlanner.fmt(plan.startMinutes()),
                    items.isEmpty() ? null : RoutePlanner.fmt(plan.endMinutes()),
                    dayCapacity == 0 ? 0 : (int) Math.round(total * 100.0 / dayCapacity),
                    plan.warnings()));
        }

        City city = trip.getCity();
        return new TripDto(trip.getId(), trip.getTitle(),
                new com.laioffer.travelplanner.dto.Dtos.CityDto(city.getId(), city.getName(), city.getCountry(),
                        city.getLat(), city.getLng(), city.getDefaultZoom(), city.getHeroEmoji(),
                        poiRepository.countByCityId(city.getId())),
                String.valueOf(trip.getStartDate()), trip.getNumDays(), trip.getDayStartHour(),
                trip.getDefaultMode().name(), days, suggestions(trip, days), planned);
    }

    /**
     * Empathy layer: say what is wrong <em>and</em> what the one-click fix would be. Emits codes and
     * parameters only — the client owns the wording and the language.
     */
    private List<SuggestionDto> suggestions(Trip trip, List<DayDto> days) {
        List<SuggestionDto> out = new ArrayList<>();
        DayDto fullest = days.stream().max(Comparator.comparingInt(DayDto::totalMinutes)).orElse(null);
        DayDto emptiest = days.stream().min(Comparator.comparingInt(DayDto::totalMinutes)).orElse(null);

        for (DayDto day : days) {
            for (NoticeDto warning : day.warnings()) {
                Map<String, String> params = new LinkedHashMap<>(warning.params());
                params.put("day", String.valueOf(day.dayIndex()));
                out.add(new SuggestionDto("DAY_WARNING", warning.code(), params,
                        null, day.dayIndex(), null));
            }
        }
        // Only offer to move things when a day is actually over its capacity. A lopsided but
        // comfortable trip is a legitimate plan, and nagging about it is how tools lose trust.
        if (fullest != null && emptiest != null && fullest.dayIndex() != emptiest.dayIndex()
                && fullest.loadPercent() > 100
                && fullest.totalMinutes() - emptiest.totalMinutes() > 180 && fullest.items().size() > 1) {
            ItemDto tail = fullest.items().reversed().stream().filter(i -> !i.locked()).findFirst().orElse(null);
            if (tail != null) {
                int delta = fullest.totalMinutes() - emptiest.totalMinutes();
                out.add(new SuggestionDto("REBALANCE", "suggestion.rebalance",
                        Map.of("fromDay", String.valueOf(fullest.dayIndex()),
                                "toDay", String.valueOf(emptiest.dayIndex()),
                                "deltaHours", String.valueOf(delta / 60),
                                "deltaMinutes", String.valueOf(delta % 60),
                                "name", tail.poi().name()),
                        tail.id(), fullest.dayIndex(), emptiest.dayIndex()));
            }
        }
        for (DayDto day : days) {
            if (day.items().isEmpty()) {
                out.add(new SuggestionDto("EMPTY_DAY", "suggestion.emptyDay",
                        Map.of("day", String.valueOf(day.dayIndex())),
                        null, day.dayIndex(), null));
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Map<Integer, List<ItineraryItem>> itemsByDay(Trip trip) {
        Map<Integer, List<ItineraryItem>> byDay = new LinkedHashMap<>();
        for (int day = 1; day <= trip.getNumDays(); day++) {
            byDay.put(day, new ArrayList<>());
        }
        for (ItineraryItem item : itemRepository.findByTripIdOrderByDayIndexAscSeqAsc(trip.getId())) {
            byDay.computeIfAbsent(item.getDayIndex(), k -> new ArrayList<>()).add(item);
        }
        return byDay;
    }

    private int dayEnd(List<ItineraryItem> items, Trip trip, TravelMode mode) {
        if (items == null || items.isEmpty()) {
            return trip.getDayStartHour() * 60;
        }
        return routePlanner.buildDay(items.stream().map(ItineraryItem::getPoi).toList(),
                mode, trip.getDayStartHour(), dayEndHour).endMinutes();
    }

    private void resequence(Long tripId, int dayIndex) {
        List<ItineraryItem> items = itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(tripId, dayIndex);
        persistOrder(items);
    }

    /**
     * Writes an order without transient unique-key collisions. First move every row to a disjoint
     * temporary range and flush, then assign the final contiguous 0..n-1 sequence.
     */
    private void persistOrder(List<ItineraryItem> ordered) {
        if (ordered.size() >= Integer.MAX_VALUE - TEMP_SEQUENCE_BASE) {
            throw new IllegalStateException("Itinerary is too large to resequence safely");
        }
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setSeq(TEMP_SEQUENCE_BASE + i);
        }
        itemRepository.saveAll(ordered);
        itemRepository.flush();

        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setSeq(i);
        }
        itemRepository.saveAll(ordered);
    }

    private void validateOptimizationOrder(List<Integer> order, List<Boolean> locked) {
        if (order == null || order.size() != locked.size()) {
            throw new IllegalStateException("Route planner returned an incomplete order");
        }
        LinkedHashSet<Integer> positions = new LinkedHashSet<>(order);
        if (positions.size() != order.size()) {
            throw new IllegalStateException("Route planner returned duplicate positions");
        }
        for (int position = 0; position < order.size(); position++) {
            Integer source = order.get(position);
            if (source == null || source < 0 || source >= order.size()) {
                throw new IllegalStateException("Route planner returned an invalid position");
            }
            if (Boolean.TRUE.equals(locked.get(position)) && source != position) {
                throw new IllegalStateException("Route planner moved locked position " + position);
            }
        }
    }

    private TravelMode resolveMode(Trip trip, String override) {
        if (override == null || override.isBlank()) {
            return trip.getDefaultMode();
        }
        return parseMode(override);
    }

    private TravelMode parseMode(String value) {
        try {
            return TravelMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("error.travelModeInvalid",
                    "Unsupported travel mode", "mode", value);
        }
    }

    private Trip load(UserEntity user, Long tripId) {
        return tripRepository.findByIdAndUserId(tripId, user.getId())
                .orElseThrow(() -> ApiException.notFound("error.tripNotFound", "Trip not found"));
    }

    private Trip loadForUpdate(UserEntity user, Long tripId) {
        return tripRepository.findOwnedForUpdate(tripId, user.getId())
                .orElseThrow(() -> ApiException.notFound("error.tripNotFound", "Trip not found"));
    }

    private ItineraryItem item(Trip trip, Long itemId) {
        return itemRepository.findByIdAndTripId(itemId, trip.getId())
                .orElseThrow(() -> ApiException.notFound("error.itemNotFound", "Item not found"));
    }

    private int requireDay(Trip trip, int dayIndex) {
        if (dayIndex < 1 || dayIndex > trip.getNumDays()) {
            throw ApiException.badRequest("error.dayOutOfRange", "Day " + dayIndex + " is outside this " + trip.getNumDays() + "-day trip", "day", dayIndex, "numDays", trip.getNumDays());
        }
        return dayIndex;
    }

    private static String hours(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return h > 0 ? (m > 0 ? h + "h" + m + "m" : h + "h") : m + "m";
    }
}
