# Optimization service-level objectives

## Measurement rules

These are initial launch objectives, not claims about current production performance. Establish a
load-test baseline before release and review the targets after two weeks of representative traffic.

- Measure server-side duration from entry to the optimization endpoint until its response is ready.
- Exclude client/network time, but include repository access, route-provider calls, optimization,
  persistence, and recomputing the returned `TripDto`.
- Report single-day and all-days endpoints separately, by stop-count bucket, algorithm, route
  provider, travel mode, cache hit/miss, and result status.
- Evaluate availability and correctness over a rolling 28-day window; evaluate latency both daily and
  over the same window. Do not hide failed or timed-out requests from latency reporting.
- Valid requests exclude authentication/authorization failures, malformed modes, missing trips, and
  invalid day indexes.

## Initial SLOs

| SLI | Objective | Scope |
| --- | --- | --- |
| Successful optimization availability | >= 99.5% | Valid single-day and all-days requests; provider fallback counts as success |
| Single-day latency, warm route cache | p95 <= 750 ms; p99 <= 1.5 s | Up to 12 stops |
| Single-day latency, route-cache miss | p95 <= 3 s; p99 <= 8 s | Up to 12 stops, including external provider/fallback |
| Large-day latency | p95 <= 2 s warm; p99 <= 8 s cold | 13–25 stops using heuristic |
| All-days latency | p95 <= 8 s; p99 <= 15 s | Up to 15 days and 60 total stops |
| Locked-slot correctness | 100% | Every locked stop remains at its original position |
| Permutation correctness | 100% | No stop is lost, duplicated, or added |
| Atomic persistence | 100% | A request persists the complete new order or no new order |
| Feasibility priority | 100% | If the exact solver can avoid closing-time overrun, its result has no overrun |

The 28-day availability error budget at 99.5% is 0.5% of valid requests. Correctness objectives have
no intentional error budget: violations are data-integrity defects and should page the owning team or
disable optimization, depending on impact.

## Required telemetry

Emit one structured event per request with these fields (names may be adapted to the metrics stack):

- `endpoint`, `trip_id_hash`, `day_count`, `stop_count`, and `locked_stop_count`;
- `algorithm`, `travel_mode`, `route_provider`, and `route_cache_result`;
- `duration_ms`, `provider_duration_ms`, and `planner_duration_ms`;
- `result` (`success`, `validation_error`, `provider_fallback`, `timeout`, `internal_error`);
- `order_changed`, `locked_slot_violations`, and `permutation_violations`.

Recommended metrics are `optimization_requests_total`, `optimization_duration_seconds`,
`route_provider_duration_seconds`, `optimization_fallback_total`, and
`optimization_contract_violation_total`. Alerts should use both fast burn (for example, 1 hour) and
slow burn (for example, 24 hours) against the 28-day availability error budget.

## Guardrails

- Keep the exact-solver limit at 12 unless benchmarks demonstrate the latency SLO on production-like
  hardware; Held-Karp grows as `O(n² × 2ⁿ)`.
- Cap accepted stops per day at 25 or introduce asynchronous jobs before supporting larger inputs.
- Configure explicit connect/read deadlines for external providers. A provider failure must fall back
  to the next configured `RouteProvider` rather than hold the request indefinitely.
- Do not persist any order that fails the permutation or locked-slot invariant.

