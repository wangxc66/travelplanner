package com.laioffer.travelplanner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Entity
@Table(name = "city", uniqueConstraints = @UniqueConstraint(
        name = "uk_city_name_country", columnNames = {"name", "country"}))
public class City {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @NotBlank
    private String name;

    @Column(nullable = false)
    @NotBlank
    private String country;

    @Column(nullable = false)
    @NotBlank
    private String timezone;

    @Column(nullable = false)
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private double lat;

    @Column(nullable = false)
    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private double lng;

    @Column(nullable = false)
    @Min(1)
    @Max(22)
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
