package com.laioffer.travelplanner.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/**
 * One POI placed on one day of a trip. {@code dayIndex} is 1-based; {@code seq} orders the visits
 * inside that day. Keeping the day as a column (instead of a separate table) means "move to another
 * day" is a single field update.
 */
@Entity
public class ItineraryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "poi_id")
    private Poi poi;

    private int dayIndex;

    private int seq;

    /** Pinned items keep their position when the day is auto-optimized. */
    private boolean locked;

    protected ItineraryItem() {
    }

    public ItineraryItem(Trip trip, Poi poi, int dayIndex, int seq) {
        this.trip = trip;
        this.poi = poi;
        this.dayIndex = dayIndex;
        this.seq = seq;
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public Poi getPoi() {
        return poi;
    }

    public int getDayIndex() {
        return dayIndex;
    }

    public void setDayIndex(int dayIndex) {
        this.dayIndex = dayIndex;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }
}
