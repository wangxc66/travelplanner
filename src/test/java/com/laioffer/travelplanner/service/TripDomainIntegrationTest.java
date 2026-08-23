package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.dto.Dtos.AddItemRequest;
import com.laioffer.travelplanner.dto.Dtos.MoveItemRequest;
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
import com.laioffer.travelplanner.repository.UserRepository;
import com.laioffer.travelplanner.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "travelplanner.h2.tcp.enabled=false",
        "travelplanner.osrm.enabled=false"
})
@ActiveProfiles("trip-domain-test")
@Transactional
class TripDomainIntegrationTest {

    @Autowired private TripService service;
    @Autowired private UserRepository userRepository;
    @Autowired private CityRepository cityRepository;
    @Autowired private PoiRepository poiRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private ItineraryItemRepository itemRepository;

    private UserEntity owner;
    private UserEntity otherUser;
    private City city;
    private Trip trip;
    private Poi a;
    private Poi b;
    private Poi c;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(new UserEntity("trip-owner", "hash", "Owner"));
        otherUser = userRepository.save(new UserEntity("other-user", "hash", "Other"));
        city = cityRepository.save(new City("Domain City", "Testland", "UTC", 10, 20, 12, "T"));
        a = poiRepository.save(poi("A", 10.1));
        b = poiRepository.save(poi("B", 10.2));
        c = poiRepository.save(poi("C", 10.3));
        trip = tripRepository.save(new Trip(owner, city, "Domain trip", LocalDate.of(2026, 8, 23),
                2, 9, TravelMode.WALK));
    }

    @Test
    void duplicateReorderIdsAreRejectedWithoutChangingTheDay() {
        ItineraryItem first = itemRepository.save(new ItineraryItem(trip, a, 1, 0));
        ItineraryItem second = itemRepository.save(new ItineraryItem(trip, b, 1, 1));
        itemRepository.flush();

        assertThatThrownBy(() -> service.reorderDay(owner, trip.getId(), 1,
                List.of(first.getId(), first.getId())))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("error.reorderMismatch");

        assertThat(day(1)).extracting(ItineraryItem::getId)
                .containsExactly(first.getId(), second.getId());
        assertContiguous(day(1));
    }

    @Test
    void crossDayMoveProducesCompleteContiguousOrders() {
        ItineraryItem first = itemRepository.save(new ItineraryItem(trip, a, 1, 0));
        ItineraryItem moving = itemRepository.save(new ItineraryItem(trip, b, 1, 1));
        ItineraryItem target = itemRepository.save(new ItineraryItem(trip, c, 2, 0));
        itemRepository.flush();

        var response = service.moveItem(owner, trip.getId(), moving.getId(), new MoveItemRequest(2, 0));

        assertThat(day(1)).extracting(ItineraryItem::getId).containsExactly(first.getId());
        assertThat(day(2)).extracting(ItineraryItem::getId)
                .containsExactly(moving.getId(), target.getId());
        assertContiguous(day(1));
        assertContiguous(day(2));
        assertThat(response.plannedCount()).isEqualTo(3);
        assertThat(response.days().get(1).items()).extracting(item -> item.id())
                .containsExactly(moving.getId(), target.getId());
    }

    @Test
    void reorderAndRemoveNeverExposeDuplicateOrGappedSequences() {
        ItineraryItem first = itemRepository.save(new ItineraryItem(trip, a, 1, 0));
        ItineraryItem second = itemRepository.save(new ItineraryItem(trip, b, 1, 1));
        ItineraryItem third = itemRepository.save(new ItineraryItem(trip, c, 1, 2));
        itemRepository.flush();

        var reordered = service.reorderDay(owner, trip.getId(), 1,
                List.of(third.getId(), first.getId(), second.getId()));
        assertThat(day(1)).extracting(ItineraryItem::getId)
                .containsExactly(third.getId(), first.getId(), second.getId());
        assertContiguous(day(1));
        assertThat(reordered.days().getFirst().items()).extracting(item -> item.id())
                .containsExactly(third.getId(), first.getId(), second.getId());

        var removed = service.removeItem(owner, trip.getId(), third.getId());
        assertThat(day(1)).extracting(ItineraryItem::getId)
                .containsExactly(first.getId(), second.getId());
        assertContiguous(day(1));
        assertThat(removed.days().getFirst().items()).extracting(item -> item.id())
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    void anotherUserCannotReadOrMutateItemsThroughTheTrip() {
        ItineraryItem item = itemRepository.saveAndFlush(new ItineraryItem(trip, a, 1, 0));

        assertThatThrownBy(() -> service.removeItem(otherUser, trip.getId(), item.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("error.tripNotFound");

        assertThat(itemRepository.findById(item.getId())).isPresent();
    }

    @Test
    void duplicatePoiAndOutOfRangeDayAreRejectedWithoutPartialState() {
        itemRepository.saveAndFlush(new ItineraryItem(trip, a, 1, 0));

        assertThatThrownBy(() -> service.addItem(owner, trip.getId(), new AddItemRequest(a.getId(), 2)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("error.poiAlreadyPlanned");

        assertThatThrownBy(() -> service.addItem(owner, trip.getId(), new AddItemRequest(b.getId(), 3)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("error.dayOutOfRange");

        assertThat(itemRepository.findByTripIdOrderByDayIndexAscSeqAsc(trip.getId()))
                .extracting(item -> item.getPoi().getId()).containsExactly(a.getId());
    }

    @Test
    void shrinkingTripFoldsRemovedDaysWithoutSequenceCollisionsAndReturnsFinalState() {
        ItineraryItem retained = itemRepository.save(new ItineraryItem(trip, a, 1, 0));
        ItineraryItem foldedFirst = itemRepository.save(new ItineraryItem(trip, b, 2, 0));
        ItineraryItem foldedSecond = itemRepository.save(new ItineraryItem(trip, c, 2, 1));
        itemRepository.flush();

        var response = service.update(owner, trip.getId(),
                new UpdateTripRequest(null, null, 1, null, null));

        assertThat(response.numDays()).isEqualTo(1);
        assertThat(response.days()).hasSize(1);
        assertThat(response.days().getFirst().items()).extracting(item -> item.id())
                .containsExactly(retained.getId(), foldedFirst.getId(), foldedSecond.getId());
        assertThat(response.plannedCount()).isEqualTo(3);
        assertThat(day(1)).extracting(ItineraryItem::getId)
                .containsExactly(retained.getId(), foldedFirst.getId(), foldedSecond.getId());
        assertContiguous(day(1));
    }

    private Poi poi(String name, double lat) {
        return new Poi(city, name, "Landmark", lat, 20, 4.5, 30, 0, 24, name);
    }

    private List<ItineraryItem> day(int day) {
        return itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(trip.getId(), day);
    }

    private static void assertContiguous(List<ItineraryItem> items) {
        assertThat(items).extracting(ItineraryItem::getSeq)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, items.size()).boxed().toList());
    }
}
