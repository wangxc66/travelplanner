# Catalog behaviour specification

Owner: Yang Han (`w3-CAT`) · Status: frozen for week 3 · Applies to `CatalogController`,
`CatalogService`, `PoiRepository`

This is the contract for city, category and POI discovery. It is the input to the OpenAPI document
(`w3-API`, Ziheng) and to the schema work (`w3-DB`, Jingyuan). Where the current implementation has a
limitation, this document names it rather than hiding it — a documented limit is a decision, an
undocumented one is a bug waiting to be filed.

Every rule below is pinned by a test. See [Test coverage](#test-coverage) for the mapping.

---

## 1. Endpoints

| Method | Path | Auth |
| --- | --- | --- |
| `GET` | `/api/cities` | public |
| `GET` | `/api/cities/{cityId}/pois` | public |
| `GET` | `/api/cities/{cityId}/categories` | public |

All three are read-only and idempotent: repeated calls never change state.

## 2. `GET /api/cities`

Returns every city, ordered by `name` ascending, each with a `poiCount`. No filtering, no paging —
the catalog holds a handful of cities by design.

## 3. `GET /api/cities/{cityId}/pois`

### 3.1 Parameters

| Parameter | Type | Default | Accepted range |
| --- | --- | --- | --- |
| `cityId` | path, integer | — | any; unknown ids yield an empty list, see §3.6 |
| `keyword` | query, string | `""` | any length, see §3.2 |
| `category` | query, string | `""` | any; unknown values yield an empty list |
| `limit` | query, integer | `60` | clamped into `1..200`, see §3.5 |

A non-integer `limit` is a type mismatch and answers `400`, handled by Spring before the controller
runs.

### 3.2 Keyword normalisation

A keyword passes through four steps before it reaches SQL:

1. **Null becomes empty.** A missing `keyword` and `keyword=` are the same request.
2. **Trim.** Leading and trailing whitespace is removed. `"  museum  "` and `"museum"` are the same
   request.
3. **Lower-case with `Locale.ROOT`.** The root locale is explicit, not incidental: under a Turkish
   default locale `"I"` lower-cases to `"ı"` and the same query would answer differently depending on
   the server's environment.
4. **Escape LIKE wildcards.** `\` becomes `\\`, `%` becomes `\%`, `_` becomes `\_`, and the query
   applies `escape '\'`.

Step 4 is what makes a keyword mean text. Before it, `keyword=%` returned every POI in the city and
`keyword=a_c` matched `abc` — the traveler typed a search term and got a wildcard.

An empty keyword after trimming means **no keyword filter**, not "match nothing".

### 3.3 What a keyword matches

A substring match, case-insensitive, against any of:

- `name`
- `category`
- `description`

Matching any one field is enough. Matching is substring, not prefix and not whole-word: `"bar"`
matches `"Sushi_Bar Nine"`. There is no relevance ranking — a name hit and a description hit are
equally good, and §3.4 alone decides the order.

### 3.4 Ordering

`rating` descending, then `name` ascending, then `id` ascending.

The `id` term makes the order **total**. Without it, two POIs sharing a rating and a name are tied,
and the database returns them in whatever order it likes — which is not guaranteed to be the same
order on H2 and PostgreSQL, nor the same order twice on PostgreSQL after the table has been updated.
A total order is what lets a client trust that the same query gives the same answer, and it is the
precondition for pushing `limit` into SQL (§3.5, deferred to week 4).

`rating` is the primary key of the sort, so an unrated POI (`rating = 0`) sorts last.

### 3.5 Result bound

`limit` is **clamped**, not rejected: `limit=0` behaves as `limit=1`, `limit=99999` as `limit=200`.
An out-of-range value is answered, not refused.

This is deliberate for week 3 — rejecting would change how existing clients behave. If the API
contract prefers `400` for out-of-range values, that is a `w3-API` decision and belongs in the same
change as the OpenAPI freeze.

The ceiling is currently applied **in the service, after the database has returned every matching
row**. The response is correctly bounded; the query is not. Pushing the ceiling into SQL is deferred
to week 4 (§7).

### 3.6 Unknown city

`GET /api/cities/999/pois` returns `200` with `[]`. So does `/categories`. The API does not currently
distinguish "this city does not exist" from "this city has no POIs".

This is a known gap, not a chosen behaviour. Changing it to `404 error.cityNotFound` is a contract
change and needs `w3-API` sign-off; it is listed in §7.

## 4. `GET /api/cities/{cityId}/categories`

Returns the distinct `category` values present in that city, ordered alphabetically. The list is
derived from the POIs that exist, so it never offers a category that would return no results.

## 5. Category filtering

- Matching is **case-insensitive and whole-value**: `Food`, `food` and `FOOD` select the same POIs.
  There is no prefix or substring matching on category.
- An empty or blank `category` means **no category filter**.
- The literal string `All` (any case) is also treated as "no filter".

`All` is a sentinel inherited from the UI, and it is the one place where an English display word
leaks into the backend. It contradicts the project's rule that the server emits semantic codes and
the client owns wording (Engineering Review §3.2). It is kept for compatibility; the intended end
state is that the client sends an empty `category` and the sentinel is removed.

## 6. POI field rules

Enforced by Bean Validation on the `Poi` entity, so violations fail on write rather than surfacing as
strange search results. All 84 seeded POIs satisfy every rule.

| Field | Rule |
| --- | --- |
| `name` | required, non-blank |
| `category` | required, non-blank |
| `lat` | −90 … 90 |
| `lng` | −180 … 180 |
| `rating` | 0 … 5 |
| `avgVisitMinutes` | greater than 0 |
| `openHour` | 0 … 24 |
| `closeHour` | 0 … 24 |

These are the JVM-side rules. The matching database `CHECK` constraints belong to `w3-DB` and are
specified in [CATALOG_QUERY_PATTERNS.md](CATALOG_QUERY_PATTERNS.md).

### 6.1 Opening hours and `openLabel`

`PoiDto.openLabel` is a language-neutral clock range; digits read the same in every language, so no
translation is needed. `alwaysOpen` is a separate boolean and the client supplies that wording.

| `openHour` | `closeHour` | `alwaysOpen` | `openLabel` |
| --- | --- | --- | --- |
| 0 | 24 | `true` | `null` |
| 9 | 18 | `false` | `09:00 – 18:00` |
| 6 | 12 | `false` | `06:00 – 12:00` |
| 9 | 24 | `false` | `09:00 – 00:00 (+1d)` |

The last row is a wart. A place open until midnight is not "always open", so it keeps a label, and
the shared time formatter renders hour 24 as next-day midnight. It is pinned by a test so it cannot
change silently, but it is not a shape the frontend should have to explain to a user.

### 6.2 Known limitation: no cross-midnight hours

The model is a single `openHour … closeHour` window per POI, and nothing in the catalog rejects
`openHour > closeHour` — such a row would simply never match a schedule sensibly. A bar open
18:00–02:00 cannot be represented. No seeded POI needs it.

Engineering Review §10 already carries this as an open question ("Single daily window does not model
weekday/holiday schedules"). Fixing it means normalised opening-hours rows, which is a schema change
well outside week 3.

## 7. Deferred to week 4 (`w4-CAT`)

Recorded here so review can see they were considered and postponed, not missed.

| Item | Why it waits |
| --- | --- |
| Push `limit` into SQL via `Pageable` | Pure performance; the response is already bounded. Belongs with the index tuning that makes it measurable. |
| Normalise the `poiSearch` cache key | The key uses raw parameters, so `"Temple"`, `"temple "` and `" TEMPLE"` occupy three entries with identical contents. Caching policy is explicitly `w4-CAT` scope. |
| Remove the N+1 in `cities()` | One count query per city. Three cities today; real cost only appears alongside the wider query tuning. |
| `404` for an unknown city (§3.6) | Contract change, needs `w3-API` agreement. |
| Reject rather than clamp `limit` (§3.5) | Contract change, same gate. |
| Expose `cityId` / raw opening hours on `PoiDto` | Contract change, same gate. The frontend cannot currently compute "open now". |
| Decouple `openLabel` from `RoutePlanner.fmt` | Layering cleanup. `RoutePlanner` is being rewritten this week by `w3-RTE-EXACT` / `w3-RTE-LARGE`; touching it now only creates conflicts. |
| Separate seed data from `DataSeeder` | Explicit `w4-CAT` scope. |

## 8. Test coverage

| Rule | Test |
| --- | --- |
| Keyword trimmed and lower-cased (§3.2) | `CatalogServiceTest.normalisesKeyword` |
| Blank keyword means no filter (§3.2) | `CatalogServiceTest.treatsBlankKeywordAsNoFilter` |
| Wildcards escaped (§3.2) | `CatalogServiceTest.escapesLikeWildcards`, `PoiRepositoryTest.treatsPercentAsLiteral`, `PoiRepositoryTest.treatsUnderscoreAsLiteral` |
| Keyword matches description (§3.3) | `PoiRepositoryTest.matchesDescription` |
| Keyword and category combine (§3.3) | `PoiRepositoryTest.combinesKeywordAndCategory` |
| Ordering (§3.4) | `PoiRepositoryTest.ordersByRatingThenName`, `PoiRepositoryTest.breaksTiesById` |
| Result bound (§3.5) | `CatalogServiceTest.boundsResultCount` |
| Service preserves repository order (§3.4) | `CatalogServiceTest.preservesRepositoryOrder` |
| Search never crosses cities (§3.3) | `PoiRepositoryTest.scopesToOneCity` |
| Category case-insensitive (§5) | `PoiRepositoryTest.matchesCategoryCaseInsensitively`, `CatalogServiceTest.passesCategoryThrough` |
| `All` sentinel (§5) | `CatalogServiceTest.treatsAllAsNoFilter` |
| Distinct alphabetical categories (§4) | `PoiRepositoryTest.listsDistinctCategories` |
| `openLabel` mapping (§6.1) | `CatalogServiceTest.mapsAlwaysOpenToNullLabel`, `mapsOpeningWindowToClockRange`, `mapsMidnightCloseAsNextDay` |
| Full DTO mapping (§6) | `CatalogServiceTest.mapsAllFields` |

Fixtures live in `CatalogFixtures`, deliberately separate from the demo seed in `DataSeeder`: the
demo data exists to look good in a browser, changes whenever someone adds a nicer museum, and happens
to contain no duplicate names, no percent signs and no underscores — exactly the cases that were
broken.

### Coverage gap

The `id` tiebreaker (§3.4) is **not** demonstrated by a failing test. Reverting it leaves the suite
green, because H2 happens to return tied rows in insertion order. The tiebreaker is a guarantee
against database-dependent behaviour, and a single-engine suite cannot reproduce the divergence it
prevents. Once the PostgreSQL profile from `w3-DB` is available, `PoiRepositoryTest` should run
against both engines; until then the rule is documented and asserted, but not proven necessary.
