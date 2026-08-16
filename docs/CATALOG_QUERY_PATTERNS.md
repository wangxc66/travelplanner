# Catalog query patterns and data rules

From: Yang Han (`w3-CAT`) · To: Jingyuan (`w3-DB`) · Status: input for the Flyway V1 baseline

`w3-DB` lists "Yang Han query patterns" as a dependency. This is that input: every query the catalog
issues, the indexes they want, and the value rules that should become database `CHECK` constraints.

Behaviour these queries implement is specified in [CATALOG_BEHAVIOR.md](CATALOG_BEHAVIOR.md).

---

## 1. Queries the catalog issues

### 1.1 POI search — the hot path

`PoiRepository.search`, behind `GET /api/cities/{cityId}/pois`.

```sql
SELECT p.* FROM poi p
WHERE p.city_id = ?
  AND (? = '' OR lower(p.name)        LIKE '%' || ? || '%' ESCAPE '\'
              OR lower(p.category)    LIKE '%' || ? || '%' ESCAPE '\'
              OR lower(p.description) LIKE '%' || ? || '%' ESCAPE '\')
  AND (? = '' OR lower(p.category) = lower(?))
ORDER BY p.rating DESC, p.name ASC, p.id ASC
```

Shape notes:

- `city_id` is always bound. No catalog query is ever cross-city.
- The empty string is the "no filter" sentinel for both `keyword` and `category`. It is not null, and
  it should stay that way — a sentinel keeps the plan stable and avoids null-typing differences
  between H2 and PostgreSQL.
- The keyword arrives already lower-cased and LIKE-escaped.
- `ORDER BY` is a **total order**. Please keep `id` as the final term in any rewrite; the frontend and
  the future SQL-side `LIMIT` both depend on it.
- The result ceiling is applied in Java today. Week 4 pushes it into SQL as `LIMIT` (default 60,
  maximum 200), which will make the `ORDER BY` index-sensitive.

### 1.2 Category list

`PoiRepository.findCategories`, behind `GET /api/cities/{cityId}/categories`.

```sql
SELECT DISTINCT p.category FROM poi p WHERE p.city_id = ? ORDER BY p.category
```

### 1.3 POI counts per city

`PoiRepository.countByCityId`, behind `GET /api/cities` and the trip DTO.

```sql
SELECT count(*) FROM poi WHERE city_id = ?
```

Called once per city today — an N+1 that week 4 replaces with a single `GROUP BY city_id`. Worth
knowing before you size the index, since the grouped form wants `poi(city_id)` to be usable alone.

### 1.4 City list

`CityRepository.findAllByOrderByNameAsc`: full table scan of a handful of rows. No index needed.

## 2. Requested indexes

| Index | Serves | Priority |
| --- | --- | --- |
| `poi(city_id)` | every catalog query; the FK on `poi.city_id` | required |
| `poi(city_id, category)` | §1.1 category filter, §1.2 distinct categories | high |
| `poi(city_id, rating DESC, name, id)` | §1.1 sort, and the week-4 `LIMIT` push-down | high |
| trigram / full-text on `poi(name)`, `poi(description)` | §1.1 keyword match | PostgreSQL only, see §2.1 |

### 2.1 On the keyword predicate

`LIKE '%term%'` is a leading-wildcard match and **no B-tree index can serve it**. At 84 POIs the scan
is free; the Engineering Review (§10) already flags it as degrading with catalog size.

For PostgreSQL the standard answer is `pg_trgm`:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX poi_name_trgm ON poi USING gin (lower(name) gin_trgm_ops);
CREATE INDEX poi_description_trgm ON poi USING gin (lower(description) gin_trgm_ops);
```

Two caveats before you commit to this:

- The extension needs privileges some managed PostgreSQL instances do not grant by default. Please
  confirm against the target environment rather than the local one.
- H2 has no equivalent, so the local profile keeps scanning. That is acceptable — but it means the
  index only ever proves itself on PostgreSQL, and any timing evidence gathered on H2 is not
  transferable.

If `pg_trgm` is not available, leave the keyword predicate unindexed for now and let me know. Making
search index-friendly would mean changing the matching semantics from substring to prefix or
full-text, and that is a behaviour change I would have to specify and re-freeze with `w3-API`, not
something to slip into a migration.

## 3. Requested `CHECK` constraints

The application enforces these through Bean Validation on the `Poi` entity. They are here so the
database enforces them too — validation in one process is a convention, a constraint is a guarantee.
All 84 seeded POIs satisfy every rule, so a fresh migration over seed data will not fail.

| Column | Constraint |
| --- | --- |
| `poi.name` | `NOT NULL`, non-empty after trim |
| `poi.category` | `NOT NULL`, non-empty after trim |
| `poi.lat` | `BETWEEN -90 AND 90` |
| `poi.lng` | `BETWEEN -180 AND 180` |
| `poi.rating` | `BETWEEN 0 AND 5` |
| `poi.avg_visit_minutes` | `> 0` |
| `poi.open_hour` | `BETWEEN 0 AND 24` |
| `poi.close_hour` | `BETWEEN 0 AND 24` |
| `poi.city_id` | `NOT NULL`, FK to `city(id)` |

Two rules I am deliberately **not** asking for:

- **`open_hour <= close_hour`.** It would permanently exclude cross-midnight opening hours
  (a bar open 18:00–02:00). No seeded POI needs it today, but that is a product decision, not a data
  hygiene one. See CATALOG_BEHAVIOR.md §6.2.
- **Uniqueness on `poi(city_id, name)`.** The catalog legitimately holds chains and branches sharing a
  name — the search fixtures rely on it, and it is why the sort needs an `id` tiebreaker at all.

## 4. Notes for the PostgreSQL profile

- `lower(p.category) = lower(?)` is a function-call comparison. If you would rather have it use
  `poi(city_id, category)` directly, a functional index on `lower(category)` works, or the column can
  be stored normalised. Either is fine by me; the behaviour contract only promises case-insensitive
  whole-value matching, not a particular storage form.
- `ORDER BY rating DESC` puts unrated POIs (`rating = 0`) last. If `rating` ever becomes nullable,
  the sort needs an explicit `NULLS LAST` — PostgreSQL defaults nulls first on `DESC`, H2 does not.
  Please keep `rating` `NOT NULL DEFAULT 0` and this stays a non-issue.

## 5. What I need back

1. Confirmation that the indexes in §2 are in the V1 baseline (or why not).
2. A yes/no on `pg_trgm` in the target environment.
3. A heads-up if any `CHECK` in §3 conflicts with a migration you have already written, so I can
   adjust the entity annotations rather than have the two drift.
