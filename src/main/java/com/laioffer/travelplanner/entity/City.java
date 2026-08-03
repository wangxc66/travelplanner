package com.laioffer.travelplanner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class City {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String country;

    @Column(nullable = false)
    private String timezone;

    private double lat;

    private double lng;

    private int defaultZoom;

    private String heroEmoji;

    protected City() {
    }

    public City(String name, String country, String timezone, double lat, double lng, int defaultZoom, String heroEmoji) {
        this.name = name;
        this.country = country;
        this.timezone = timezone;
        this.lat = lat;
        this.lng = lng;
        this.defaultZoom = defaultZoom;
        this.heroEmoji = heroEmoji;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCountry() {
        return country;
    }

    public String getTimezone() {
        return timezone;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public int getDefaultZoom() {
        return defaultZoom;
    }

    public String getHeroEmoji() {
        return heroEmoji;
    }
}
