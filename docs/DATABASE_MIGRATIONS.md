# Database migrations and persistence

## Ownership and startup order

Flyway is the only owner of database DDL. On startup it applies immutable scripts from
`src/main/resources/db/migration` in version order; only then does Hibernate run with
`ddl-auto: validate`. A missing column, incompatible type, or stale entity mapping therefore fails
startup instead of silently changing production data.

`V1__baseline_schema.sql` is portable between PostgreSQL and H2 in PostgreSQL mode. It creates all
tables, named primary/foreign keys, checks, business unique rules, and indexes required by current
repository access paths.

## Environments

Local development defaults to in-memory H2 and the `demo-seed` profile:

```bash
./gradlew bootRun
```

PostgreSQL uses environment-supplied credentials and never activates demo seed implicitly:

```bash
DB_URL=jdbc:postgresql://localhost:5432/travelplanner \
DB_USER=travelplanner \
DB_PASSWORD='replace-me' \
./gradlew bootRun --args='--spring.profiles.active=postgres'
```

The Java `DataSeeder` contains catalog demo data only—never accounts or trips. It runs exclusively
under `demo-seed`, finds cities by the `(name, country)` natural key, and inserts only missing POI
names. Do not combine `demo-seed` with a production profile. Production/reference catalog changes
must use reviewed versioned migrations or a separate controlled import job.

## Adding a migration

1. Never edit a migration that has been applied in a shared environment; Flyway checksums detect it.
2. Add the next file, for example `V2__add_trip_status.sql`.
3. Make expansion changes backward compatible first: add nullable columns/indexes, deploy code that
   can read both shapes, backfill, then enforce `NOT NULL` in a later migration.
4. Name every constraint and index. Add indexes for foreign keys and measured query predicates—not
   for every column.
5. Run `./gradlew test`; `DatabaseMigrationIntegrationTest` creates a blank database, migrates it,
   and proves Hibernate validation can start.
6. Test the migration against a disposable PostgreSQL database before merging and inspect the query
   plan for changed high-traffic queries with `EXPLAIN (ANALYZE, BUFFERS)`.

## Existing database baseline

The baseline script is intended for a clean database. Before introducing Flyway to an existing
database, back it up, compare its schema with V1, reconcile all differences, and then baseline it at
version 1 using an approved one-time Flyway operation. Never enable automatic `baseline-on-migrate`
globally: it can hide deployment to the wrong non-empty schema.

## Rollback and recovery

Community Flyway migrations are forward-only. Every production migration needs a written recovery
choice before deployment:

- **Preferred:** roll the application back while leaving an additive, backward-compatible schema in
  place, then ship a compensating `Vnext` migration.
- **Destructive or incompatible change:** stop writes, restore the verified pre-deployment PostgreSQL
  snapshot/PITR point, deploy the prior application, and verify row counts and critical queries.
- Never delete a Flyway history row or manually edit an applied schema to make a failed deployment
  appear successful. `flyway repair` is only for correcting history after the underlying cause and
  exact target state have been reviewed.

Before migration, record a backup/PITR marker, current Flyway version, row counts, and expected lock
duration. After migration, verify Flyway success, application health, Hibernate validation, FK/unique
constraint behavior, and p95 latency of catalog search, trip list, and itinerary-day reads.

## Current access paths

| Repository operation | Supporting database path |
| --- | --- |
| Trip list for one user | `idx_trip_user_id_desc` plus one grouped summary/count query |
| Load trip owned by user | `idx_trip_user_id_desc`; city fetched in the same query graph |
| Ordered trip/day items | `idx_itinerary_trip_day_seq` |
| Prevent duplicate POI in a trip | `uk_itinerary_item_trip_poi` |
| Prevent duplicate day positions | `uk_itinerary_item_trip_day_seq` |
| Catalog by city/category/rating | `idx_poi_city_category`, `idx_poi_city_rating_name` |
| Username lookup/login | `uk_app_user_username` |

Leading-wildcard keyword search (`%keyword%`) cannot efficiently use a normal B-tree. If catalog size
outgrows the current curated dataset, add a PostgreSQL-specific trigram/full-text migration based on
measured workload rather than enabling an extension in the portable baseline.
