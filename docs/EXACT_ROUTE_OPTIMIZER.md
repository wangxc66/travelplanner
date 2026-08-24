# Exact route optimizer — Week 3 correctness and Week 4 integration

## Contract

`RoutePlanner.optimizeOrder` receives the complete ordered day plus one locked flag per stop. It
returns indices into that same list. `optimizeDetailed` preserves that contract and additionally
returns the algorithm, optimality guarantee, objective before/after, algorithm-only latency and
retained-label metrics. A locked stop at position `p` must therefore satisfy
`result[p] == p`; it is never removed from the travel matrix or the schedule calculation.

The objective is the strict tuple:

1. total minutes of visits that occur after each venue's closing time;
2. final departure time, including travel, opening-time waits, and visits;
3. total travel minutes, when the first two values tie;
4. original-index path, used only as a deterministic final tie-break.

For a visit interval `[start, finish]`, the primary penalty is
`max(0, finish - max(start, close))`. This measures actual visit minutes after closing and caps the
penalty at the visit duration even when arrival is long after closing.

## Exact algorithm (at most 12 movable stops)

The Held-Karp mask contains movable stops only. The solver still walks every full route position; at a
locked position it has exactly one legal next stop. At an unlocked position it may select any
unvisited movable stop.

A single value per `(mask, last)` is insufficient for soft time windows. For example, one prefix may
have fewer closed minutes but finish later, while another has more closed minutes but finishes early
enough to avoid a larger violation at the next stop. The implementation therefore stores a tie-aware
retained set of labels `(closedMinutes, finishMinutes, travelMinutes, path)` for every state.
Label A can discard B only when it is safe for every possible continuation. When closed minutes are
equal, an earlier-clock prefix may discard a later one only if its travel time and deterministic path
are also no worse: a later opening-time wait can otherwise synchronize their clocks, making either the
shorter-travel prefix or the smaller path the correct final tie-break. Final selection uses the strict
lexicographic objective above.

With at most `P` retained labels per state, memory is `O(n·2ⁿ·P)`. Transition generation is
`O(n²·2ⁿ·P)` and the current linear frontier check gives a conservative worst-case bound of
`O(n²·2ⁿ·P²)`. `P` can grow in constructed cases, so Week 4 exposes generated/accepted/pruned
labels, maximum per-state retained-label count and peak current-layer label count. The layer metric is
a search-width indicator, not a JVM heap measurement, because parent references keep ancestors alive.
Labels now retain an immutable parent reference plus a compact deterministic tie code instead of
copying a complete `int[]` path at every transition; the winning path is reconstructed once.

The exact frontier is never silently truncated. If a future product-level resource budget is added,
crossing it must return `optimal=false` or an explicit error; silently switching a `<=12` request to a
heuristic would violate the exact-solver contract.

## Larger-route fallback

More than 12 movable stops use deterministic earliest-completion greedy followed by 2-opt. Both
operate on the full route with locked slots in place. The user's existing order is also scored and is
kept when the greedy seed is worse, so the fallback cannot regress the documented objective.

## Week 4 service integration

Optimize responses retain the existing `TripDto` JSON shape and add an `optimizationResults` list.
Each result includes the day, mode, algorithm, `optimal`/`changed` flags, objective before/after
(including travel minutes) and metrics. Existing clients can ignore the additive field; an updated
client can explain why a route changed instead of comparing only raw travel minutes with a different
business objective.

`TripService` writes only items whose sequence number actually changed. An identity result performs
no persistence write, so a second Optimize request is idempotent at the ordering/persistence layer.
`optimizeAllDays` invokes the private optimize-and-persist helper for every day and renders one final
trip response instead of rebuilding the entire trip after every day.

A request-level travel-mode override now drives both the matrix optimization and the returned day
timeline. It does not mutate the trip's stored default mode.

## Verification fixtures

`RoutePlannerExactTest` provides fixed matrices rather than geographic estimates. It covers:

- the two-stop boundary that the former early return skipped;
- a soft-window counterexample requiring two Pareto labels for one Held-Karp state;
- a locked first stop whose following movable stop must remain movable;
- multiple locked slots, deterministic ties, and the 12/13 strategy boundary;
- a known Pareto counterexample plus 25 seeded six-stop/lock fixtures checked against brute force;
- detailed before/after objectives and algorithm-only timing with a deterministic test clock;
- a tied closed-minutes/finish-time fixture that selects the route with less travel;
- an opening-wait synchronization fixture where all three numeric objectives tie and path order wins;
- real 13-stop heuristic execution and 13 total stops with 12 movable stops on the exact branch.

`TripServiceOptimizeDayTest` verifies full-day lock integration, changed-only writes, identity/repeat
idempotency, mode-override consistency and one-response `optimizeAllDays` behavior.

`RoutePlannerSloTest` reports 4/8/12-stop fixed-matrix scaling. The 12-movable-stop boundary runs 20
warmed-up samples and enforces the assignment's `P95 <= 500 ms` target without provider/network time.
See `WEEK4_EXACT_ROUTE_BENCHMARK.md` for the recorded result and metric definitions.

The optimizer validates that the provider returned a square, non-negative matrix. An
`Integer.MAX_VALUE` unreachable-edge sentinel is rejected before clock arithmetic; the current
Google/OSRM providers are responsible for replacing unreachable pairs with their deterministic
estimated-route fallback.

Run the checks with:

```bash
./gradlew test
```

Provider/network time is outside the exact-algorithm latency SLO. The fixed-matrix fixtures keep
correctness tests independent from Google/OSRM availability and cache state.
