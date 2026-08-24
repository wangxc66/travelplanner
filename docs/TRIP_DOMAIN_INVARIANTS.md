# Trip domain mutation rules

## Invariants

Every committed trip must satisfy all of these rules:

- the caller owns the trip being read or changed;
- every itinerary item belongs to the same city as its trip;
- a POI appears at most once in a trip;
- `dayIndex` is between 1 and the trip's current `numDays`;
- each day has one item per `seq`, ordered contiguously from `0`;
- locked items remain in their slots during optimization;
- a mutation commits its entire new state or rolls back completely;
- every successful mutation returns a `TripDto` assembled from the final transactional state.

Database constraints enforce the rules that can be expressed within one row/table. Service validation
enforces ownership, trip-relative day bounds, city matching, exact reorder membership, and optimizer
output. Keeping both layers is intentional: validation produces useful API errors while constraints
remain the last line of defense against races or future code paths.

## Transaction and concurrency boundary

All mutations are `@Transactional`. They load the owned trip through
`TripRepository.findOwnedForUpdate`, which executes a pessimistic write lock on the trip row. Mutations
for different trips can proceed concurrently; mutations for the same trip serialize in commit order.
The lock is acquired only after ownership is included in the query, so a caller cannot lock or infer
another user's trip.

This trip-level lock protects add/remove/reorder/move/lock/update/optimize/rebalance as one consistency
domain. The database unique rules independently reject duplicate `(trip_id, poi_id)` and duplicate
`(trip_id, day_index, seq)` values.

## Collision-safe ordering

An immediate unique constraint cannot swap `seq=0` and `seq=1` with direct updates: the first update
would temporarily collide with the row that still owns the destination. `persistOrder` therefore uses
two phases inside one transaction:

1. move every affected item into a disjoint high temporary sequence range and flush;
2. write the final contiguous `0..n-1` order.

No temporary state is visible to other transactions. Any exception rolls both phases back. Remove,
reorder, same/cross-day move, optimize, and rebalance all converge on this ordering helper.

## Operation behavior

| Operation | Important guarantees |
| --- | --- |
| Create/update/delete | day count 1–15; start hour 5–14; valid mode; shrink folds later stops into the new last day |
| Add | owned trip, valid day, POI exists and matches city, no duplicate POI |
| Remove | item is resolved by both item ID and owned trip ID; remaining day is resequenced |
| Reorder | request contains every current day item exactly once—duplicates and foreign IDs fail |
| Move | item remains in its trip, target day is valid, target position is non-negative, both days become contiguous |
| Lock | only an item inside the owned trip can be toggled |
| Optimize day/all | complete permutation is validated; locked slots cannot move; all-days returns only the final DTO |
| Rebalance | cross-day moves and subsequent optimization share one lock and one transaction |

## Tests

- `TripDomainIntegrationTest` covers duplicate reorder IDs, ownership isolation, duplicate POIs, day
  bounds, cross-day moves, contiguous database ordering, and final `TripDto` consistency.
- `TripConcurrencyIntegrationTest` starts two simultaneous duplicate adds and proves one succeeds, one
  returns conflict, and one correctly ordered item remains.
- `TripServiceOptimizeDayTest` verifies full locked-route integration and rejects invalid optimizer
  permutations before the first write.
- `DatabaseMigrationIntegrationTest` proves the clean schema reaches Flyway V2 and contains both order
  uniqueness rules before Hibernate validation succeeds.

