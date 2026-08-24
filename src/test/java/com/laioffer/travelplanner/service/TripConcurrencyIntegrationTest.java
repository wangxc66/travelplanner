package com.laioffer.travelplanner.service;

import com.laioffer.travelplanner.dto.Dtos.AddItemRequest;
import com.laioffer.travelplanner.entity.City;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:trip_concurrency;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "travelplanner.h2.tcp.enabled=false",
        "travelplanner.osrm.enabled=false"
})
@ActiveProfiles("trip-concurrency-test")
class TripConcurrencyIntegrationTest {

    @Autowired private TripService service;
    @Autowired private UserRepository userRepository;
    @Autowired private CityRepository cityRepository;
    @Autowired private PoiRepository poiRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private ItineraryItemRepository itemRepository;

    @Test
    void concurrentDuplicateAddsSerializeAtTheTripAndLeaveOneValidItem() throws Exception {
        UserEntity owner = userRepository.save(new UserEntity("concurrent-owner", "hash", "Owner"));
        City city = cityRepository.save(new City("Concurrency City", "Testland", "UTC",
                10, 20, 12, "C"));
        Poi poi = poiRepository.save(new Poi(city, "Only once", "Landmark", 10, 20,
                4.5, 30, 0, 24, "Only once"));
        Trip trip = tripRepository.save(new Trip(owner, city, "Concurrent trip",
                LocalDate.of(2026, 8, 23), 1, 9, TravelMode.WALK));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> addAfterGate(owner, trip.getId(), poi.getId(), ready, start));
            Future<Object> second = executor.submit(() -> addAfterGate(owner, trip.getId(), poi.getId(), ready, start));
            ready.await();
            start.countDown();

            List<Object> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes.stream().filter(result -> result instanceof ApiException).toList())
                    .singleElement()
                    .satisfies(result -> assertThat(((ApiException) result).getCode())
                            .isEqualTo("error.poiAlreadyPlanned"));
            assertThat(outcomes.stream().filter(result -> !(result instanceof ApiException)).toList())
                    .hasSize(1);
        }

        var persisted = itemRepository.findByTripIdAndDayIndexOrderBySeqAsc(trip.getId(), 1);
        assertThat(persisted).hasSize(1);
        assertThat(persisted.getFirst().getPoi().getId()).isEqualTo(poi.getId());
        assertThat(persisted.getFirst().getSeq()).isZero();
    }

    private Object addAfterGate(UserEntity owner, Long tripId, Long poiId,
                                CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            return service.addItem(owner, tripId, new AddItemRequest(poiId, 1));
        } catch (ApiException exception) {
            return exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
