# Week 4 exact-route benchmark report

## Scope

This report measures only the in-process optimizer after the route matrix has been returned and
validated. It excludes provider/network, database and route-leg rendering time, matching the
assignment's `P95 <= 500 ms` definition for at most 12 movable stops.

The fixtures use deterministic asymmetric matrices and time windows. Each size is warmed before
measurement; 4/8-stop fixtures use 10 samples and the 12-stop boundary uses 20 samples.

## Representative local result

Environment: Darwin arm64, Temurin JDK 24.0.2 toolchain, Gradle 9.5.1. Values are expected to vary by
machine; the test owns the 500 ms pass/fail assertion.

| Movable stops | P50 | P95 | Generated labels | Accepted labels | Max state labels | Peak layer labels |
|---:|---:|---:|---:|---:|---:|---:|
| 4 | <0.1 ms | <0.1 ms | 55 | 39 | 2 | 15 |
| 8 | 0.4 ms | 0.4 ms | 5,031 | 2,562 | 7 | 423 |
| 12 | 22.4 ms | 46.8 ms | 237,663 | 96,536 | 13 | 9,973 |

The 12-stop P95 is about 9.4% of the 500 ms budget in this run. The label counts also show why the
solver must retain a tie-aware label set rather than one label per state and why the exact threshold
should not be increased without new measurements.

## Metric definitions

- `generatedLabels`: transition candidates evaluated by exact Held-Karp.
- `acceptedLabels`: generated candidates inserted into a state's retained-label set.
- `prunedLabels`: generated candidates rejected as duplicate/worse or dominated.
- `maxFrontierSize`: largest retained-label count in one `(mask,last)` state (`P` in the design
  complexity); travel/path ties can intentionally retain labels beyond a two-dimensional Pareto set.
- `peakFrontierLabelsInLayer`: largest current-layer retained-label count. This is search width, not
  total JVM live objects or heap usage, because parent references retain earlier labels.
- `algorithmNanos`: objective scoring plus search time, starting after `RouteProvider.matrix` returns.

Heuristic (`>12` movable stops) and fixed-order (`<=1` movable stop) results report zero label metrics.
Heuristic results explicitly set `optimal=false`.

## Correctness and integration evidence

The same suite verifies:

- exact results against brute force for 25 deterministic six-stop fixtures with locks;
- a known counterexample that requires multiple Pareto labels in one state;
- equal closed-minutes/end-time routes selecting the lower-travel result;
- an opening-time synchronization counterexample that protects the deterministic final path tie-break;
- 2-stop optimization, first/middle/multiple locked slots and deterministic ties;
- real 13-stop greedy + 2-opt execution and the 12-movable/13-total lock boundary;
- before/after objective summaries and one matrix request per optimization;
- identity/repeated optimization without duplicate persistence writes;
- request mode used consistently by both optimization and returned timeline.

Run the complete verification with:

```bash
./gradlew test --no-daemon
```
