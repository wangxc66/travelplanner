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
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TripService {

    private static final int MAX_DAYS = 15;

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
        return tripRepository.findByUserIdOrderByIdDesc(user.getId()).stream()
                .map(t -> new TripSummaryDto(t.getId(), t.getTitle(), t.getCity().getName(),
                        t.getCity().getHeroEmoji(), String.valueOf(t.getStartDate()), t.getNumDays(),
                        itemRepository.findByTripIdOrderByDayIndexAscSeqAsc(t.getId()).size()))
                .toList();
    }

    @Transactional(readOnly = true)
    public TripDto get(UserEntity user, Long tripId) {
        return detail(load(user, tripId));
    }

    @Transactional
    public TripDto update(UserEntity user, Long tripId, UpdateTripRequest request) {
        Trip trip = load(user, tripId);
        if (request.title() != null && !request.title().isBlank()) {
            trip.setTitle(request.title().trim());
        }
        if (request.startDate() != null) {
            trip.setStartDate(request.startDate());
        }
        if (request.dayStartHour() != null) {
            trip.setDayStartHour(Math.clamp(request.dayStartHour(), 5, 14));
        }
        if (request.defaultMode() != null) {
            trip.setDefaultMode(TravelMode.valueOf(request.defaultMode().toUpperCase(Locale.ROOT)));
        }
        if (request.numDays() != null) {
            int days = request.numDays();
            if (days < 1 || days > MAX_DAYS) {
                throw ApiException.badRequest("error.tripDaysRange", "A trip must be between 1 and " + MAX_DAYS + " days", "max", MAX_DAYS);
            }
            if (days < trip.getNumDays()) {
                // Shrinking the trip must not silently drop stops: fold them into the last day.
                List<ItineraryItem> orphans = itemRepository.findByTripIdOrderByDayIndexAscSeqAsc(tripId).stream()
                        .filter(i -> i.getDayIndex() > days)
                        .toList();
                int seq = itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(tripId, days).size();
                for (ItineraryItem orphan : orphans) {
                    orphan.setDayIndex(days);
                    orphan.setSeq(seq++);
                }
                itemRepository.saveAll(orphans);
            }
            trip.setNumDays(days);
        }
        tripRepository.save(trip);
        return detail(trip);
    }

    @Transactional
    public void delete(UserEntity user, Long tripId) {
        tripRepository.delete(load(user, tripId));
    }

    // ------------------------------------------------------------------ itinerary items

    @Transactional
    public TripDto addItem(UserEntity user, Long tripId, AddItemRequest request) {
        Trip trip = load(user, tripId);
        int day = requireDay(trip, request.dayIndex());
        Poi poi = poiRepository.findById(request.poiId())
                .orElseThrow(() -> ApiException.notFound("error.poiNotFound", "POI not found"));
        if (!poi.getCity().getId().equals(trip.getCity().getId())) {
            throw ApiException.badRequest("error.poiWrongCity", "That place is not in " + trip.getCity().getName(), "city", trip.getCity().getName());
        }
        if (itemRepository.existsByTripIdAndPoiId(tripId, poi.getId())) {
            throw ApiException.conflict("error.poiAlreadyPlanned", poi.getName() + " is already in this trip", "name", poi.getName());
        }
        int seq = itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(tripId, day).size();
        itemRepository.save(new ItineraryItem(trip, poi, day, seq));
        return detail(trip);
    }

    @Transactional
    public TripDto removeItem(UserEntity user, Long tripId, Long itemId) {
        Trip trip = load(user, tripId);
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
        Trip trip = load(user, tripId);
        int day = requireDay(trip, dayIndex);
        Map<Long, ItineraryItem> current = new LinkedHashMap<>();
        for (ItineraryItem item : itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(tripId, day)) {
            current.put(item.getId(), item);
        }
        if (itemIds.size() != current.size() || !current.keySet().containsAll(itemIds)) {
            throw ApiException.badRequest("error.reorderMismatch", "Reorder must list exactly the items of day " + day, "day", day);
        }
        int seq = 0;
        for (Long id : itemIds) {
            current.get(id).setSeq(seq++);
        }
        itemRepository.saveAll(current.values());
        return detail(trip);
    }

    /** Drag across days, or the one-click "move to day N" from a rebalance suggestion. */
    @Transactional
    public TripDto moveItem(UserEntity user, Long tripId, Long itemId, MoveItemRequest request) {
        Trip trip = load(user, tripId);
        ItineraryItem item = item(trip, itemId);
        int targetDay = requireDay(trip, request.dayIndex());
        int sourceDay = item.getDayIndex();

        List<ItineraryItem> target = new ArrayList<>(
                itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(tripId, targetDay));
        target.remove(item);
        int position = request.seq() == null ? target.size() : Math.clamp(request.seq(), 0, target.size());
        target.add(position, item);
        item.setDayIndex(targetDay);
        for (int i = 0; i < target.size(); i++) {
            target.get(i).setSeq(i);
        }
        itemRepository.saveAll(target);
        itemRepository.flush();
        if (sourceDay != targetDay) {
            resequence(tripId, sourceDay);
        }
        return detail(trip);
    }

    @Transactional
    public TripDto toggleLock(UserEntity user, Long tripId, Long itemId) {
        Trip trip = load(user, tripId);
        ItineraryItem item = item(trip, itemId);
        item.setLocked(!item.isLocked());
        itemRepository.save(item);
        return detail(trip);
    }

    // ------------------------------------------------------------------ the smart bits

    /**
     * Re-orders one day so total travel time is minimal, then persists it. Items the traveler pinned
     * keep their slot: only the unpinned ones are permuted, which is what "lock my hotel first, then
     * do whatever is fastest" means in practice.
     */
    @Transactional
    public TripDto optimizeDay(UserEntity user, Long tripId, int dayIndex, String modeOverride) {
        Trip trip = load(user, tripId);
        int day = requireDay(trip, dayIndex);
        TravelMode mode = resolveMode(trip, modeOverride);
        List<ItineraryItem> items = itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(tripId, day);
        if (items.size() < 3) {
            return detail(trip);
        }

        List<Integer> lockedPositions = new ArrayList<>();
        List<ItineraryItem> movable = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isLocked()) {
                lockedPositions.add(i);
            } else {
                movable.add(items.get(i));
            }
        }
        if (movable.size() < 3) {
            return detail(trip);
        }

        List<Poi> pois = movable.stream().map(ItineraryItem::getPoi).toList();
        boolean lockFirst = !lockedPositions.isEmpty() && lockedPositions.getFirst() == 0;
        List<Integer> order = routePlanner.optimizeOrder(pois, mode, trip.getDayStartHour(), lockFirst);

        List<ItineraryItem> reordered = order.stream().map(movable::get).toList();
        ItineraryItem[] slots = new ItineraryItem[items.size()];
        for (int position : lockedPositions) {
            slots[position] = items.get(position);
        }
        int cursor = 0;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                slots[i] = reordered.get(cursor++);
            }
        }
        for (int i = 0; i < slots.length; i++) {
            slots[i].setSeq(i);
        }
        itemRepository.saveAll(List.of(slots));
        return detail(trip);
    }

    @Transactional
    public TripDto optimizeAllDays(UserEntity user, Long tripId, String modeOverride) {
        Trip trip = load(user, tripId);
        for (int day = 1; day <= trip.getNumDays(); day++) {
            optimizeDay(user, tripId, day, modeOverride);
        }
        return detail(load(user, tripId));
    }

    /**
     * Moves the trailing stop off every over-full day onto the emptiest day that still has room.
     * Returns the updated trip; the caller decides whether to apply or only preview the suggestions.
     */
    @Transactional
    public TripDto rebalance(UserEntity user, Long tripId) {
        Trip trip = load(user, tripId);
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
            moveItem(user, tripId, candidate.getId(), new MoveItemRequest(lightest, null));
            movedAnything = true;
        }

        Trip fresh = load(user, tripId);
        if (movedAnything) {
            for (int day = 1; day <= fresh.getNumDays(); day++) {
                optimizeDay(user, tripId, day, null);
            }
        }
        return detail(load(user, tripId));
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
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setSeq(i);
        }
        itemRepository.saveAll(items);
    }

    private TravelMode resolveMode(Trip trip, String override) {
        if (override == null || override.isBlank()) {
            return trip.getDefaultMode();
        }
        return TravelMode.valueOf(override.toUpperCase(Locale.ROOT));
    }

    private Trip load(UserEntity user, Long tripId) {
        return tripRepository.findByIdAndUserId(tripId, user.getId())
                .orElseThrow(() -> ApiException.notFound("error.tripNotFound", "Trip not found"));
    }

    private ItineraryItem item(Trip trip, Long itemId) {
        return itemRepository.findById(itemId)
                .filter(i -> i.getTrip().getId().equals(trip.getId()))
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
