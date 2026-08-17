# Exact route optimizer — Week 3 design note

## Contract

`RoutePlanner.optimizeOrder` receives the complete ordered day plus one locked flag per stop. It
returns indices into that same list. A locked stop at position `p` must therefore satisfy
`result[p] == p`; it is never removed from the travel matrix or the schedule calculation.

The objective is the strict tuple:

1. total minutes of visits that occur after each venue's closing time;
2. final departure time, including travel, opening-time waits, and visits;
3. original-index path, used only as a deterministic tie-break.

For a visit interval `[start, finish]`, the primary penalty is
`max(0, finish - max(start, close))`. This measures actual visit minutes after closing and caps the
penalty at the visit duration even when arrival is long after closing.

## Exact algorithm (at most 12 movable stops)

The Held-Karp mask contains movable stops only. The solver still walks every full route position; at a
locked position it has exactly one legal next stop. At an unlocked position it may select any
unvisited movable stop.

A single value per `(mask, last)` is insufficient for soft time windows. For example, one prefix may
have fewer closed minutes but finish later, while another has more closed minutes but finishes early
enough to avoid a larger violation at the next stop. The implementation therefore stores the Pareto
frontier of labels `(closedMinutes, finishMinutes)` for every state. Label A can discard B only when A
is no worse in both dimensions. Final selection uses the strict lexicographic objective above.

With at most `P` non-dominated labels per state, memory is `O(n·2ⁿ·P)`. Transition generation is
`O(n²·2ⁿ·P)` and the current linear frontier check gives a conservative worst-case bound of
`O(n²·2ⁿ·P²)`. `P` can grow in constructed cases, so Week 4 should benchmark label counts and add an
explicit deterministic budget/fallback before increasing the 12-stop threshold.

## Larger-route fallback

More than 12 movable stops use deterministic earliest-completion greedy followed by 2-opt. Both
operate on the full route with locked slots in place. The user's existing order is also scored and is
kept when the greedy seed is worse, so the fallback cannot regress the documented objective.

## Verification fixtures

`RoutePlannerExactTest` provides fixed matrices rather than geographic estimates. It covers:

- the two-stop boundary that the former early return skipped;
- a soft-window counterexample requiring two Pareto labels for one Held-Karp state;
- a locked first stop whose following movable stop must remain movable;
- multiple locked slots, deterministic ties, and the 12/13 strategy boundary;
- a known Pareto counterexample plus 25 seeded six-stop/lock fixtures checked against brute force.

`TripServiceOptimizeDayTest` verifies that persistence integration sends the complete day and lock
flags to the solver and writes every returned stop back with a contiguous sequence.

`RoutePlannerSloTest` runs 20 warmed-up exact solves at the 12-movable-stop boundary against a fixed
matrix and enforces the assignment's `P95 <= 500 ms` target without provider/network time.

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
