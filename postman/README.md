# Provider profiling with Postman

1. Start the backend with `./gradlew bootRun` (OSRM) or
   `./gradlew bootRun --args='--travelplanner.osrm.enabled=false'` (offline estimator).
2. Import both JSON files in this directory into Postman.
3. Select the **TravelPlanner Local** environment.
4. Open **TravelPlanner Provider Profiling**, click **Run collection**, and run the entire collection.

The collection creates four independent trips containing 5, 13, 20, and 25 POIs. It checks that
optimization keeps every POI exactly once, does not increase closing-warning count, and does not end
later when warning counts tie. Each scenario is optimized three times so the final request can reuse
the cache key produced by the reordered itinerary.

Open the Postman Console to see the final response summary. Provider latency and cache/fallback data
are emitted by the Spring Boot process as `route_provider_call ...` log lines.

This workflow creates a unique test account and four trips in the in-memory H2 database. Restarting
the backend removes them.
