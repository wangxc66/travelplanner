# TripCanvas — backend

Plan a city trip day by day: search a POI catalog, drop stops on a map, and let the planner work out
the order that actually fits in a day. This repository is the service; the UI lives in
**[travelplanner-frontend](https://github.com/wangxc66/travelplanner-frontend)**.

Spring Boot behind the TripCanvas trip planner. Same stack shape as the staybooking / twitch
projects: Java + Spring Boot + Gradle, JWT auth via Spring Security, Spring Data JPA, Caffeine cache.

The one interesting problem here is [ordering a day](#design-notes): it is an open-path Travelling
Salesman Problem *with time windows*, solved exactly with Held-Karp bitmask DP for the sizes a human
actually plans. Start with [`RoutePlanner`](src/main/java/com/laioffer/travelplanner/service/RoutePlanner.java).

Running it start to finish, in Chinese: [USAGE.md](USAGE.md).
Working on it with us: [CONTRIBUTING.md](CONTRIBUTING.md).
Trip mutation, ownership, ordering, and concurrency guarantees:
[trip domain invariants](docs/TRIP_DOMAIN_INVARIANTS.md).

## Run

```bash
./gradlew bootRun
```

Starts on `http://localhost:8080` against an in-memory H2 database migrated by Flyway and populated by
the local-only `demo-seed` profile — no Docker or local database needed. Seed content is the searchable
catalog only: 3 cities and 84 POIs. Accounts and trips are created through the app.

H2 console: `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:travelplanner`, user `sa`).

### PostgreSQL instead

```bash
./gradlew bootRun --args='--spring.profiles.active=postgres'
```

Expects `travelplanner` on `localhost:5432`. Flyway applies versioned migrations and Hibernate runs in
`ddl-auto: validate` mode. See [database migrations and recovery](docs/DATABASE_MIGRATIONS.md).

## Tests

```bash
./gradlew test
```

`RoutePlannerTest` covers the basic scheduling behavior. `RoutePlannerExactTest` adds fixed-matrix
brute-force, Pareto-window, deterministic tie, two-stop, lock, and 12/13 boundary fixtures;
`TripServiceOptimizeDayTest` verifies lock-aware persistence integration. `RoutePlannerSloTest` checks
the 12-stop exact solver against the fixed-matrix `P95 <= 500 ms` target. `PolylineCodecTest` checks the
encoded-polyline codec against Google's reference example and round-trips leg stitching.

<a id="design-notes"></a>

## Design notes

**POIs live in our own database, not the Places API.** The brief asks for database-backed POI search,
and keeping Places off the critical path also keeps the API bill flat. Google Maps is used for what it
is uniquely good at: rendering, overlays, routing.

**Routing sits behind one interface.** `RouteProvider` supplies the travel-time matrix the optimizer
searches over, and the per-leg duration / distance / geometry the timeline and map render.

- `GoogleRoutesProvider` — the real thing, via the Google **Routes API**. Two endpoints:
  `computeRouteMatrix` for the n×n matrix, and one `computeRoutes` call per ordered day (middle stops
  passed as `intermediates`) which returns each leg's duration, distance and encoded polyline. That
  polyline is what makes the map follow actual streets and rail instead of cutting through buildings.
  Both results are cached in Caffeine on the exact coordinate set + travel mode, so repeatedly hitting
  Optimize on one day costs nothing after the first call. Transit and traffic depend on *when* you
  travel, so `departureTime` is the next 09:00 **in the city's own timezone** — otherwise a Tokyo
  itinerary gets priced against 3 a.m. service levels.
- `OsrmRouteProvider` — **the keyless default.** Real street geometry and real road distance from an
  OSRM server, via the same two calls (`/table` for the matrix, one `/route` per day). OSRM returns
  geometry per navigation *step*, so each itinerary leg is stitched back together by `PolylineCodec`.
  Driving durations are OSRM's own; walking and transit durations are derived from the real road
  distance, because the public demo server's pedestrian profile returns car-like times and OSRM has no
  concept of trains — transit borrows the driving corridor for its shape, which beats a straight line
  across the bay but is not a rail alignment.
- `EstimatedRouteProvider` — last resort: great-circle distance with a per-mode detour factor and fixed
  overhead. No network, no geometry, so the map draws straight lines.

`RouteProviderConfig` picks the best available at startup and logs which. Each tier is also the failure
fallback for the tier above it — a bad key or an unreachable OSRM degrades route *quality* instead of
breaking the planner, and Google's per-pair "no route" answers fall back individually so the matrix is
never sparse.

```bash
# real geometry AND real durations for every mode
GOOGLE_MAPS_API_KEY=your-key ./gradlew bootRun

# your own OSRM instead of the public demo server
OSRM_BASE_URL=http://localhost:5000 ./gradlew bootRun
```

The Google key needs the **Routes API** enabled on the Cloud project. The matrix is billed per element
(n² per day), which is exactly why both providers cache on the coordinate set plus travel mode — repeat
Optimize clicks on one day cost nothing. The public OSRM server is a courtesy demo with no SLA and is
explicitly not for production; `travelplanner.osrm.enabled: false` turns it off.

**Ordering a day is TSP with time windows.** `RoutePlanner` minimizes, lexicographically, (1) minutes
spent inside a closed venue and (2) the time the day ends — which counts travel and waiting together.
Minimizing raw travel alone produces schedules that are geometrically tidy and practically useless: it
will send you to a bar that opens at 18:00 first thing in the morning. For at most 12 movable stops it
uses multi-label Held-Karp: every `(visited set, last stop)` retains the non-dominated `(closed minutes,
finish time)` labels needed for an exact soft-window result. Locked stops remain in the full route and
are forced into their original slots. If a state has at most `P` labels, storage is `O(n·2ⁿ·P)` and the
current list-based dominance checks are `O(n²·2ⁿ·P²)` in the worst case. Above 12 movable stops it
falls back to deterministic earliest-completion greedy plus 2-opt scored on the full schedule.

**One read shape.** Every mutating endpoint returns the entire recomputed `TripDto` — days, clock
timeline, per-leg distance, warnings, suggestions. The client never has to reconcile partial updates,
which is why drag-and-drop, optimize and rebalance all stay in sync with the map for free.

**No presentation strings cross the wire.** The server knows a stop would run past closing time; it does
not decide the language or the wording. Every user-facing message is a `NoticeDto` — a semantic code
plus parameters — and `ApiException` carries the same, alongside an English message the client uses only
as a fallback for codes it does not recognise:

```json
{"code": "warning.closesEarly", "params": {"closesAt": "18:00"}}
{"message": "That place is not in Tokyo", "code": "error.poiWrongCity", "params": {"city": "Tokyo"}}
```

The same principle removed three other leaks: the weekday name (the client formats it from the ISO
date), the "Open anytime" label (now an `alwaysOpen` boolean), and duration formatting. The payoff is
that the whole product switches language instantly with no redeploy, and all copy lives in one place.

## API

| Method | Path | Notes |
| --- | --- | --- |
| POST | `/auth/register`, `/auth/login` | returns a JWT |
| GET | `/api/cities` | public |
| GET | `/api/cities/{id}/pois?keyword=&category=&limit=` | public, cached |
| GET | `/api/cities/{id}/categories` | public |
| GET | `/api/trips` | trips of the caller |
| POST | `/api/trips` | `{cityId, title, startDate, numDays}` (1–15) |
| GET | `/api/trips/{id}` | full plan with timeline + warnings |
| PATCH | `/api/trips/{id}` | title / date / numDays / dayStartHour / defaultMode |
| POST | `/api/trips/{id}/items` | `{poiId, dayIndex}` |
| DELETE | `/api/trips/{id}/items/{itemId}` | |
| POST | `/api/trips/{id}/items/{itemId}/move` | `{dayIndex, seq}` — cross-day drag |
| POST | `/api/trips/{id}/items/{itemId}/lock` | pin a slot against Optimize |
| PUT | `/api/trips/{id}/days/{day}/order` | `{itemIds}` — drag-and-drop within a day |
| POST | `/api/trips/{id}/days/{day}/optimize` | reorder one day |
| POST | `/api/trips/{id}/optimize` | reorder every day |
| POST | `/api/trips/{id}/rebalance` | move stops off over-full days, then reorder |

## Shape

```
entity/      City, Poi, Trip, ItineraryItem, UserEntity, TravelMode
repository/  Spring Data JPA interfaces; POI search is one JPQL query
security/    JwtService, JwtAuthFilter, SecurityConfig
service/     AuthService, CatalogService, TripService
             RoutePlanner            scheduling + TSP with time windows
             RouteProvider           Google | Osrm | Estimated, chosen at startup
             PolylineCodec           encoded-polyline encode/decode
             TravelTimeEstimator     speed model for modes a router cannot time
web/         controllers + ApiException/GlobalExceptionHandler
config/      CacheConfig (Caffeine), RouteProviderConfig, DataSeeder
db/migration Flyway baseline and subsequent forward migrations
```
