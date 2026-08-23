package com.laioffer.travelplanner.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trip")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_trip_user"))
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_trip_city"))
    private City city;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private LocalDate startDate;

    /** 1 - 15 days, enforced at the service layer. */
    @Column(nullable = false)
    private int numDays;

    @Column(nullable = false)
    private int dayStartHour;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TravelMode defaultMode;

    /**
     * Never read directly — the service loads items through {@code ItineraryItemRepository}. The
     * mapping exists so that deleting a trip cascades to its stops instead of failing on the foreign
     * key.
     */
    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ItineraryItem> items = new ArrayList<>();

    protected Trip() {
    }

    public Trip(UserEntity user, City city, String title, LocalDate startDate, int numDays,
                int dayStartHour, TravelMode defaultMode) {
        this.user = user;
        this.city = city;
        this.title = title;
        this.startDate = startDate;
        this.numDays = numDays;
        this.dayStartHour = dayStartHour;
        this.defaultMode = defaultMode;
    }

    public Long getId() {
        return id;
    }

    public UserEntity getUser() {
        return user;
    }

    public City getCity() {
        return city;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public int getNumDays() {
        return numDays;
    }

    public void setNumDays(int numDays) {
        this.numDays = numDays;
    }

    public int getDayStartHour() {
        return dayStartHour;
    }

    public void setDayStartHour(int dayStartHour) {
        this.dayStartHour = dayStartHour;
    }

    public TravelMode getDefaultMode() {
        return defaultMode;
    }

    public void setDefaultMode(TravelMode defaultMode) {
        this.defaultMode = defaultMode;
    }
}
