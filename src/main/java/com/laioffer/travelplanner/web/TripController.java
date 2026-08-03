package com.laioffer.travelplanner.web;

import com.laioffer.travelplanner.dto.Dtos.AddItemRequest;
import com.laioffer.travelplanner.dto.Dtos.CreateTripRequest;
import com.laioffer.travelplanner.dto.Dtos.MoveItemRequest;
import com.laioffer.travelplanner.dto.Dtos.ReorderRequest;
import com.laioffer.travelplanner.dto.Dtos.TripDto;
import com.laioffer.travelplanner.dto.Dtos.TripSummaryDto;
import com.laioffer.travelplanner.dto.Dtos.UpdateTripRequest;
import com.laioffer.travelplanner.service.AuthService;
import com.laioffer.travelplanner.service.TripService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/trips")
public class TripController {

    private final TripService tripService;
    private final AuthService authService;

    public TripController(TripService tripService, AuthService authService) {
        this.tripService = tripService;
        this.authService = authService;
    }

    @GetMapping
    public List<TripSummaryDto> list() {
        return tripService.list(authService.currentUser());
    }

    @PostMapping
    public TripDto create(@Valid @RequestBody CreateTripRequest request) {
        return tripService.create(authService.currentUser(), request);
    }

    @GetMapping("/{tripId}")
    public TripDto get(@PathVariable Long tripId) {
        return tripService.get(authService.currentUser(), tripId);
    }

    @PatchMapping("/{tripId}")
    public TripDto update(@PathVariable Long tripId, @RequestBody UpdateTripRequest request) {
        return tripService.update(authService.currentUser(), tripId, request);
    }

    @DeleteMapping("/{tripId}")
    public void delete(@PathVariable Long tripId) {
        tripService.delete(authService.currentUser(), tripId);
    }

    @PostMapping("/{tripId}/items")
    public TripDto addItem(@PathVariable Long tripId, @Valid @RequestBody AddItemRequest request) {
        return tripService.addItem(authService.currentUser(), tripId, request);
    }

    @DeleteMapping("/{tripId}/items/{itemId}")
    public TripDto removeItem(@PathVariable Long tripId, @PathVariable Long itemId) {
        return tripService.removeItem(authService.currentUser(), tripId, itemId);
    }

    @PostMapping("/{tripId}/items/{itemId}/move")
    public TripDto moveItem(@PathVariable Long tripId, @PathVariable Long itemId,
                            @Valid @RequestBody MoveItemRequest request) {
        return tripService.moveItem(authService.currentUser(), tripId, itemId, request);
    }

    @PostMapping("/{tripId}/items/{itemId}/lock")
    public TripDto toggleLock(@PathVariable Long tripId, @PathVariable Long itemId) {
        return tripService.toggleLock(authService.currentUser(), tripId, itemId);
    }

    @PutMapping("/{tripId}/days/{dayIndex}/order")
    public TripDto reorder(@PathVariable Long tripId, @PathVariable int dayIndex,
                           @Valid @RequestBody ReorderRequest request) {
        return tripService.reorderDay(authService.currentUser(), tripId, dayIndex, request.itemIds());
    }

    @PostMapping("/{tripId}/days/{dayIndex}/optimize")
    public TripDto optimizeDay(@PathVariable Long tripId, @PathVariable int dayIndex,
                               @RequestParam(required = false) String mode) {
        return tripService.optimizeDay(authService.currentUser(), tripId, dayIndex, mode);
    }

    @PostMapping("/{tripId}/optimize")
    public TripDto optimizeAll(@PathVariable Long tripId, @RequestParam(required = false) String mode) {
        return tripService.optimizeAllDays(authService.currentUser(), tripId, mode);
    }

    @PostMapping("/{tripId}/rebalance")
    public TripDto rebalance(@PathVariable Long tripId) {
        return tripService.rebalance(authService.currentUser(), tripId);
    }
}
