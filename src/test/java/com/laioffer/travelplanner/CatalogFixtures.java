package com.laioffer.travelplanner;

import com.laioffer.travelplanner.entity.City;
import com.laioffer.travelplanner.entity.Poi;

import java.util.List;

/**
 * A tiny, deliberately awkward catalog for catalog tests.
 *
 * <p>Deliberately not the demo seed in {@code DataSeeder}: that data exists to make the app look
 * good in a browser, it changes whenever someone adds a nicer museum, and it happens to contain no
 * duplicate names, no percent signs and no underscores — precisely the cases catalog search gets
 * wrong. Every row below earns its place by pinning one rule in {@code docs/CATALOG_BEHAVIOR.md}.
 */
public final class CatalogFixtures {

    private CatalogFixtures() {
    }

    public static City testville() {
        return new City("Testville", "Testland", "UTC", 10.0, 20.0, 12, "🧪");
    }

    public static City otherville() {
        return new City("Otherville", "Testland", "UTC", 30.0, 40.0, 12, "🗺");
    }

    /**
     * Eight POIs, ordered here the way {@code PoiRepository.search} must return them:
     * rating descending, then name ascending, then id ascending.
     */
    public static List<Poi> testvillePois(City city) {
        return List.of(
                // Highest rating — always first, whatever else changes.
                new Poi(city, "Beta Park", "Park", 10.10, 20.10, 4.9, 60, 0, 24,
                        "Open around the clock"),
                // Two rows sharing name *and* rating: only the id tiebreaker separates them.
                new Poi(city, "Alpha Museum", "Museum", 10.20, 20.20, 4.5, 90, 9, 18,
                        "Quiet halls and a good cafe"),
                new Poi(city, "Alpha Museum", "Museum", 10.21, 20.21, 4.5, 90, 10, 17,
                        "A second branch with the same name and the same rating"),
                new Poi(city, "Night Tower", "Viewpoint", 10.30, 20.30, 4.4, 45, 9, 24,
                        "Closes at midnight"),
                // Keyword lives only in the description, never in name or category.
                new Poi(city, "Gamma Cafe", "Food", 10.40, 20.40, 4.2, 30, 8, 20,
                        "Serves espresso and pastries"),
                // A literal underscore: must not behave as a single-character wildcard.
                new Poi(city, "Sushi_Bar Nine", "Food", 10.50, 20.50, 4.1, 60, 11, 22,
                        "Counter seating only"),
                new Poi(city, "Morning Market", "Food", 10.60, 20.60, 4.0, 45, 6, 12,
                        "Early hours, cash only"),
                // A literal percent sign: must not behave as a match-everything wildcard.
                new Poi(city, "50% Off Outlet", "Shopping", 10.70, 20.70, 3.5, 75, 10, 20,
                        "Discount mall by the ring road"));
    }

    /** One POI in a second city, to prove search never leaks across city boundaries. */
    public static Poi othervillePoi(City city) {
        return new Poi(city, "Alpha Museum", "Museum", 30.10, 40.10, 5.0, 90, 9, 18,
                "Same name, different city");
    }
}
