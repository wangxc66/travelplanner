package com.laioffer.travelplanner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

/** A point of interest, searchable from our own database (no Places API call needed). */
@Entity
public class Poi {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id")
    private City city;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String category;

    private double lat;

    private double lng;

    private double rating;

    /** Typical time a traveler spends here, drives the day timeline. */
    private int avgVisitMinutes;

    /** Simplified opening window in local hours, 0 and 24 meaning "always open". */
    private int openHour;

    private int closeHour;

    @Column(length = 400)
    private String description;

    protected Poi() {
    }

    public Poi(City city, String name, String category, double lat, double lng, double rating,
               int avgVisitMinutes, int openHour, int closeHour, String description) {
        this.city = city;
        this.name = name;
        this.category = category;
        this.lat = lat;
        this.lng = lng;
        this.rating = rating;
        this.avgVisitMinutes = avgVisitMinutes;
        this.openHour = openHour;
        this.closeHour = closeHour;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public City getCity() {
        return city;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public double getRating() {
        return rating;
    }

    public int getAvgVisitMinutes() {
        return avgVisitMinutes;
    }

    public int getOpenHour() {
        return openHour;
    }

    public int getCloseHour() {
        return closeHour;
    }

    public String getDescription() {
        return description;
    }

    public boolean isAlwaysOpen() {
        return openHour == 0 && closeHour == 24;
    }
}
