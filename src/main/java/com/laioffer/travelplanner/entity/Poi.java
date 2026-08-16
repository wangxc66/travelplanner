package com.laioffer.travelplanner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

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
    @NotBlank
    private String name;

    @Column(nullable = false)
    @NotBlank
    private String category;

    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private double lat;

    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private double lng;

    @DecimalMin("0.0")
    @DecimalMax("5.0")
    private double rating;

    /** Typical time a traveler spends here, drives the day timeline. */
    @Positive
    private int avgVisitMinutes;

    /** Simplified opening window in local hours, 0 and 24 meaning "always open". */
    @Min(0)
    @Max(24)
    private int openHour;

    @Min(0)
    @Max(24)
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
