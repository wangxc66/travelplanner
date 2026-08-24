package com.laioffer.travelplanner.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripServiceOptimizeDayTest {

    private static final long USER_ID = 7L;
    private static final long CITY_ID = 11L;
    private static final long TRIP_ID = 42L;
    private static final int DAY_INDEX = 1;
    private static final int DAY_START_HOUR = 9;
    private static final int DAY_END_HOUR = 21;

    @Mock
    private TripRepository tripRepository;

    @Mock
    private ItineraryItemRepository itemRepository;

    @Mock
    private CityRepository cityRepository;

    @Mock
    private PoiRepository poiRepository;

    @Mock
    private RoutePlanner routePlanner;

    private UserEntity user;
    private City city;
    private Trip trip;
    private TripService service;

    @BeforeEach
    void setUp() {
        user = new UserEntity("chenfei", "password", "Chenfei");
        city = new City("Test City", "Test Country", "UTC", 0, 0, 12, "*");
        trip = new Trip(user, city, "Optimizer Test", LocalDate.of(2026, 8, 16),
                1, DAY_START_HOUR, TravelMode.DRIVE);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(city, "id", CITY_ID);
        ReflectionTestUtils.setField(trip, "id", TRIP_ID);

        service = new TripService(tripRepository, itemRepository, cityRepository, poiRepository,
                routePlanner, DAY_START_HOUR, DAY_END_HOUR);

        when(tripRepository.findByIdAndUserId(TRIP_ID, USER_ID)).thenReturn(Optional.of(trip));
        when(routePlanner.buildDay(anyList(), any(TravelMode.class), anyInt(), anyInt()))
                .thenAnswer(invocation -> emptyDayPlan(
                        invocation.<List<Poi>>getArgument(0).size(),
                        invocation.getArgument(2)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void passesTheFullDayAndLockFlagsWithoutPinningTheFirstMovableItem() {
        ItineraryItem hotel = item("Hotel", 0, true);
        ItineraryItem far = item("Far", 1, false);
        ItineraryItem near = item("Near", 2, false);
        ItineraryItem mid = item("Mid", 3, false);
        List<ItineraryItem> original = List.of(hotel, far, near, mid);
        stubItems(original);

        // Keep Hotel in slot zero, but deliberately move Far, the first movable item, to the end.
        when(routePlanner.optimizeDetailed(anyList(), eq(TravelMode.DRIVE), eq(DAY_START_HOUR), anyList()))
                .thenReturn(optimization(List.of(0, 2, 3, 1), 3));

        service.optimizeDay(user, TRIP_ID, DAY_INDEX, null);

        ArgumentCaptor<List<Poi>> poisCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Boolean>> locksCaptor = ArgumentCaptor.forClass(List.class);
        verify(routePlanner).optimizeDetailed(poisCaptor.capture(), eq(TravelMode.DRIVE),
                eq(DAY_START_HOUR), locksCaptor.capture());

        assertEquals(original.stream().map(ItineraryItem::getPoi).toList(), poisCaptor.getValue(),
                "locked POIs must remain in the full optimization input");
        assertEquals(List.of(true, false, false, false), locksCaptor.getValue(),
                "only the actual hotel slot is locked");
        verify(routePlanner, never()).optimizeOrder(anyList(), any(TravelMode.class), anyInt(), anyBoolean());

        List<ItineraryItem> saved = captureSavedItems();
        assertEquals(List.of("Near", "Mid", "Far"), names(saved),
                "only items whose seq changed should be written");
        assertEquals(List.of("Hotel", "Near", "Mid", "Far"), sortedNames(original));
        assertSame(hotel, original.stream().min(Comparator.comparingInt(ItineraryItem::getSeq)).orElseThrow());
        assertEquals(3, far.getSeq(), "the first movable item must be allowed to move");
        assertFalse(near.isLocked(), "the first movable slot must not gain an implicit lock");
        assertSequentialSeq(original.stream().sorted(Comparator.comparingInt(ItineraryItem::getSeq)).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesMultipleLockedSlotsAndTheirSequenceNumbers() {
        ItineraryItem a = item("A", 0, false);
        ItineraryItem lockedOne = item("Locked-1", 1, true);
        ItineraryItem b = item("B", 2, false);
        ItineraryItem c = item("C", 3, false);
        ItineraryItem lockedTwo = item("Locked-2", 4, true);
        ItineraryItem d = item("D", 5, false);
        List<ItineraryItem> original = List.of(a, lockedOne, b, c, lockedTwo, d);
        stubItems(original);

        when(routePlanner.optimizeDetailed(anyList(), eq(TravelMode.DRIVE), eq(DAY_START_HOUR), anyList()))
                .thenReturn(optimization(List.of(5, 1, 3, 2, 4, 0), 4));

        service.optimizeDay(user, TRIP_ID, DAY_INDEX, null);

        ArgumentCaptor<List<Poi>> poisCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Boolean>> locksCaptor = ArgumentCaptor.forClass(List.class);
        verify(routePlanner).optimizeDetailed(poisCaptor.capture(), eq(TravelMode.DRIVE),
                eq(DAY_START_HOUR), locksCaptor.capture());
        assertEquals(original.stream().map(ItineraryItem::getPoi).toList(), poisCaptor.getValue());
        assertEquals(List.of(false, true, false, false, true, false), locksCaptor.getValue());

        List<ItineraryItem> saved = captureSavedItems();
        assertEquals(List.of("D", "C", "B", "A"), names(saved),
                "locked items stayed in place and therefore need no persistence write");
        assertEquals(List.of("D", "Locked-1", "C", "B", "Locked-2", "A"), sortedNames(original));
        assertTrue(lockedOne.isLocked());
        assertTrue(lockedTwo.isLocked());
        assertEquals(1, lockedOne.getSeq());
        assertEquals(4, lockedTwo.getSeq());
        assertSequentialSeq(original.stream().sorted(Comparator.comparingInt(ItineraryItem::getSeq)).toList());
    }

    @Test
    void doesNotPersistAnIdentityOptimization() {
        List<ItineraryItem> original = List.of(
                item("A", 0, false), item("B", 1, false), item("C", 2, false));
        stubItems(original);
        when(routePlanner.optimizeDetailed(anyList(), eq(TravelMode.DRIVE), eq(DAY_START_HOUR), anyList()))
                .thenReturn(optimization(List.of(0, 1, 2), 3));

        var response = service.optimizeDay(user, TRIP_ID, DAY_INDEX, null);

        verify(itemRepository, never()).saveAll(any());
        assertEquals(List.of(0, 1, 2), original.stream().map(ItineraryItem::getSeq).toList());
        assertEquals(1, response.optimizationResults().size());
        assertFalse(response.optimizationResults().getFirst().changed());
    }

    @Test
    void repeatedOptimizationPersistsOnlyTheFirstChange() {
        List<ItineraryItem> original = List.of(
                item("A", 0, false), item("B", 1, false), item("C", 2, false));
        stubItems(original);
        when(routePlanner.optimizeDetailed(anyList(), eq(TravelMode.DRIVE), eq(DAY_START_HOUR), anyList()))
                .thenReturn(optimization(List.of(1, 0, 2), 3),
                        optimization(List.of(0, 1, 2), 3));

        service.optimizeDay(user, TRIP_ID, DAY_INDEX, null);
        service.optimizeDay(user, TRIP_ID, DAY_INDEX, null);

        verify(routePlanner, times(2)).optimizeDetailed(
                anyList(), eq(TravelMode.DRIVE), eq(DAY_START_HOUR), anyList());
        verify(itemRepository, times(1)).saveAll(any());
        assertEquals(List.of("B", "A", "C"), sortedNames(original));
    }

    @Test
    void usesModeOverrideForBothOptimizationAndReturnedTimeline() {
        List<ItineraryItem> original = List.of(item("A", 0, false), item("B", 1, false));
        stubItems(original);
        when(routePlanner.optimizeDetailed(anyList(), eq(TravelMode.TRANSIT), eq(DAY_START_HOUR), anyList()))
                .thenReturn(optimization(List.of(0, 1), 2));

        var response = service.optimizeDay(user, TRIP_ID, DAY_INDEX, "transit");

        verify(routePlanner).optimizeDetailed(anyList(), eq(TravelMode.TRANSIT),
                eq(DAY_START_HOUR), anyList());
        verify(routePlanner).buildDay(anyList(), eq(TravelMode.TRANSIT),
                eq(DAY_START_HOUR), eq(DAY_END_HOUR));
        assertEquals(TravelMode.DRIVE, trip.getDefaultMode(),
                "a one-request override must not mutate the trip default");
        assertEquals(1, response.optimizationResults().size());
        assertEquals("TRANSIT", response.optimizationResults().getFirst().mode());
        assertNotNull(response.optimizationResults().getFirst().metrics());
    }

    @Test
    void optimizeAllBuildsTheTripOnceAndReturnsOneSummaryPerDay() {
        trip.setNumDays(2);
        List<ItineraryItem> dayOne = List.of(
                item("A", 1, 0, false), item("B", 1, 1, false));
        List<ItineraryItem> dayTwo = List.of(
                item("C", 2, 0, false), item("D", 2, 1, false));
        when(itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(TRIP_ID, 1)).thenReturn(dayOne);
        when(itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(TRIP_ID, 2)).thenReturn(dayTwo);
        when(itemRepository.findByTripIdOrderByDayIndexAscSeqAsc(TRIP_ID))
                .thenReturn(java.util.stream.Stream.concat(dayOne.stream(), dayTwo.stream()).toList());
        when(routePlanner.optimizeDetailed(anyList(), eq(TravelMode.TRANSIT), eq(DAY_START_HOUR), anyList()))
                .thenAnswer(invocation -> {
                    int size = invocation.<List<Poi>>getArgument(0).size();
                    return optimization(java.util.stream.IntStream.range(0, size).boxed().toList(), size);
                });

        var response = service.optimizeAllDays(user, TRIP_ID, "transit");

        verify(routePlanner, times(2)).optimizeDetailed(
                anyList(), eq(TravelMode.TRANSIT), eq(DAY_START_HOUR), anyList());
        verify(routePlanner, times(2)).buildDay(
                anyList(), eq(TravelMode.TRANSIT), eq(DAY_START_HOUR), eq(DAY_END_HOUR));
        verify(itemRepository, never()).saveAll(any());
        assertEquals(List.of(1, 2), response.optimizationResults().stream()
                .map(com.laioffer.travelplanner.dto.Dtos.OptimizationSummaryDto::dayIndex).toList());
        assertTrue(response.optimizationResults().stream()
                .allMatch(result -> result.mode().equals("TRANSIT")));
    }

    private ItineraryItem item(String name, int seq, boolean locked) {
        return item(name, DAY_INDEX, seq, locked);
    }

    private ItineraryItem item(String name, int dayIndex, int seq, boolean locked) {
        Poi poi = new Poi(city, name, "Landmark", 0, 0, 4.5,
                30, 0, 24, name);
        ItineraryItem item = new ItineraryItem(trip, poi, dayIndex, seq);
        item.setLocked(locked);
        return item;
    }

    private void stubItems(List<ItineraryItem> items) {
        when(itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(TRIP_ID, DAY_INDEX))
                .thenAnswer(ignored -> items.stream()
                        .sorted(Comparator.comparingInt(ItineraryItem::getSeq))
                        .toList());
        when(itemRepository.findByTripIdOrderByDayIndexAscSeqAsc(TRIP_ID))
                .thenAnswer(ignored -> items.stream()
                        .sorted(Comparator.comparingInt(ItineraryItem::getSeq))
                        .toList());
    }

    @SuppressWarnings("unchecked")
    private List<ItineraryItem> captureSavedItems() {
        ArgumentCaptor<Iterable<ItineraryItem>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(itemRepository).saveAll(captor.capture());
        List<ItineraryItem> saved = new ArrayList<>();
        captor.getValue().forEach(saved::add);
        return saved;
    }

    private static List<String> names(List<ItineraryItem> items) {
        return items.stream().map(item -> item.getPoi().getName()).toList();
    }

    private static List<String> sortedNames(List<ItineraryItem> items) {
        return items.stream()
                .sorted(Comparator.comparingInt(ItineraryItem::getSeq))
                .map(item -> item.getPoi().getName())
                .toList();
    }

    private static RoutePlanner.OptimizationResult optimization(List<Integer> order, int movableStops) {
        RoutePlanner.RouteObjective before = new RoutePlanner.RouteObjective(30, 900, 120);
        RoutePlanner.RouteObjective after = new RoutePlanner.RouteObjective(
                order.equals(java.util.stream.IntStream.range(0, order.size()).boxed().toList()) ? 30 : 0,
                order.equals(java.util.stream.IntStream.range(0, order.size()).boxed().toList()) ? 900 : 840,
                order.equals(java.util.stream.IntStream.range(0, order.size()).boxed().toList()) ? 120 : 90);
        return new RoutePlanner.OptimizationResult(order, RoutePlanner.Algorithm.HELD_KARP, true,
                before, after,
                new RoutePlanner.OptimizationMetrics(movableStops, 1_000_000L,
                        10, 8, 2, 2, 4));
    }

    private static void assertSequentialSeq(List<ItineraryItem> items) {
        for (int i = 0; i < items.size(); i++) {
            assertEquals(i, items.get(i).getSeq(), "seq mismatch at saved position " + i);
        }
    }

    private static RoutePlanner.DayPlan emptyDayPlan(int stopCount, int dayStartHour) {
        int start = dayStartHour * 60;
        List<RoutePlanner.StopPlan> stops = new ArrayList<>(stopCount);
        for (int i = 0; i < stopCount; i++) {
            int arrive = start + i * 30;
            stops.add(new RoutePlanner.StopPlan(arrive, arrive + 30,
                    0, 0, null, List.of()));
        }
        return new RoutePlanner.DayPlan(stops, stopCount * 30, 0,
                start, start + stopCount * 30, List.of());
    }
}
