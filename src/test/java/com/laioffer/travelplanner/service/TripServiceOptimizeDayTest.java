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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
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

        when(tripRepository.findOwnedForUpdate(TRIP_ID, USER_ID)).thenReturn(Optional.of(trip));
        lenient().when(routePlanner.buildDay(anyList(), any(TravelMode.class), anyInt(), anyInt()))
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
        when(routePlanner.optimizeOrder(anyList(), eq(TravelMode.DRIVE), eq(DAY_START_HOUR), anyList()))
                .thenReturn(List.of(0, 2, 3, 1));

        service.optimizeDay(user, TRIP_ID, DAY_INDEX, null);

        ArgumentCaptor<List<Poi>> poisCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Boolean>> locksCaptor = ArgumentCaptor.forClass(List.class);
        verify(routePlanner).optimizeOrder(poisCaptor.capture(), eq(TravelMode.DRIVE),
                eq(DAY_START_HOUR), locksCaptor.capture());

        assertEquals(original.stream().map(ItineraryItem::getPoi).toList(), poisCaptor.getValue(),
                "locked POIs must remain in the full optimization input");
        assertEquals(List.of(true, false, false, false), locksCaptor.getValue(),
                "only the actual hotel slot is locked");
        verify(routePlanner, never()).optimizeOrder(anyList(), any(TravelMode.class), anyInt(), anyBoolean());

        List<ItineraryItem> saved = captureSavedItems();
        assertEquals(List.of("Hotel", "Near", "Mid", "Far"), names(saved));
        assertSame(hotel, saved.get(0));
        assertEquals(3, far.getSeq(), "the first movable item must be allowed to move");
        assertFalse(saved.get(1).isLocked(), "the first movable slot must not gain an implicit lock");
        assertSequentialSeq(saved);
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

        when(routePlanner.optimizeOrder(anyList(), eq(TravelMode.DRIVE), eq(DAY_START_HOUR), anyList()))
                .thenReturn(List.of(5, 1, 3, 2, 4, 0));

        service.optimizeDay(user, TRIP_ID, DAY_INDEX, null);

        ArgumentCaptor<List<Poi>> poisCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Boolean>> locksCaptor = ArgumentCaptor.forClass(List.class);
        verify(routePlanner).optimizeOrder(poisCaptor.capture(), eq(TravelMode.DRIVE),
                eq(DAY_START_HOUR), locksCaptor.capture());
        assertEquals(original.stream().map(ItineraryItem::getPoi).toList(), poisCaptor.getValue());
        assertEquals(List.of(false, true, false, false, true, false), locksCaptor.getValue());

        List<ItineraryItem> saved = captureSavedItems();
        assertEquals(List.of("D", "Locked-1", "C", "B", "Locked-2", "A"), names(saved));
        assertSame(lockedOne, saved.get(1));
        assertSame(lockedTwo, saved.get(4));
        assertTrue(lockedOne.isLocked());
        assertTrue(lockedTwo.isLocked());
        assertEquals(1, lockedOne.getSeq());
        assertEquals(4, lockedTwo.getSeq());
        assertSequentialSeq(saved);
    }

    @Test
    void rejectsAnInvalidOptimizerPermutationBeforeAnyWrite() {
        List<ItineraryItem> original = List.of(
                item("A", 0, false), item("B", 1, false), item("C", 2, false));
        stubItems(original);
        when(routePlanner.optimizeOrder(anyList(), eq(TravelMode.DRIVE), eq(DAY_START_HOUR), anyList()))
                .thenReturn(List.of(0, 0, 2));

        assertThrows(IllegalStateException.class,
                () -> service.optimizeDay(user, TRIP_ID, DAY_INDEX, null));

        verify(itemRepository, never()).saveAll(anyList());
        verify(itemRepository, never()).flush();
    }

    private ItineraryItem item(String name, int seq, boolean locked) {
        Poi poi = new Poi(city, name, "Landmark", 0, 0, 4.5,
                30, 0, 24, name);
        ItineraryItem item = new ItineraryItem(trip, poi, DAY_INDEX, seq);
        item.setLocked(locked);
        return item;
    }

    private void stubItems(List<ItineraryItem> items) {
        when(itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(TRIP_ID, DAY_INDEX))
                .thenReturn(items);
        lenient().when(itemRepository.findByTripIdOrderByDayIndexAscSeqAsc(TRIP_ID))
                .thenAnswer(ignored -> items.stream()
                        .sorted(Comparator.comparingInt(ItineraryItem::getSeq))
                        .toList());
    }

    @SuppressWarnings("unchecked")
    private List<ItineraryItem> captureSavedItems() {
        ArgumentCaptor<Iterable<ItineraryItem>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(itemRepository, times(2)).saveAll(captor.capture());
        List<ItineraryItem> saved = new ArrayList<>();
        captor.getAllValues().getLast().forEach(saved::add);
        return saved;
    }

    private static List<String> names(List<ItineraryItem> items) {
        return items.stream().map(item -> item.getPoi().getName()).toList();
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
