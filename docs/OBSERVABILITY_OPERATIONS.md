# Observability and performance operations handoff

## Request tracing

Every HTTP request accepts `X-Correlation-ID`. Values must match `[A-Za-z0-9._-]{1,64}`; missing or
unsafe values are replaced with a UUID. The chosen value is returned in the response, stored in the
SLF4J MDC as `correlation_id`, included in structured logs, and forwarded to Google Routes or OSRM.
MDC cleanup happens in `finally`, preventing identifiers leaking between pooled request threads.

Search logs by `correlation_id` to join the request-completion event, provider degradation messages,
security/exception events, and downstream provider logs when the provider preserves that header.

## Structured events and safe logging

Console output uses Spring Boot's Logstash JSON format. The request completion event contains only:
`event`, `method`, URL path (never query string), status, duration, and correlation ID.

Never log authorization/cookie headers, JWTs, Google keys, request or response bodies, usernames,
trip titles, POI search terms, full query strings, or latitude/longitude lists. Provider failures log
only a provider/operation label and exception class—not raw exception messages or upstream bodies.
Do not add correlation IDs, trip IDs, user IDs, URLs, coordinates, or exception messages as metric
tags; their unbounded cardinality can exhaust the metrics backend.

## Metrics and endpoints

Actuator exposes `health`, `info`, `metrics`, and `prometheus`. Only `/actuator/health` is public;
metrics and Prometheus require the same JWT authentication as protected API requests. In production,
prefer a private management network or a dedicated scraper security chain.

Key series:

- `http.server.requests`: request count/latency/status/route from Spring MVC;
- `travelplanner.planner`: `optimize` and `build_day` timers;
- `travelplanner.route.provider`: provider, matrix/legs operation, travel mode, outcome and duration;
- `cache.gets`, `cache.puts`, `cache.evictions`, and cache size for Caffeine caches, including
  `travelMatrix`, `poiSearch`, `cities`, and `categories`;
- JVM, process, datasource, and executor metrics supplied by Actuator.

Suggested dashboards show HTTP p50/p95/p99 by route, planner and provider p50/p95 by operation,
provider error rate, route-cache hit ratio, datasource pool saturation, JVM pause time, and heap.
Alert on fast/slow SLO burn, provider error spikes, cache hit-ratio collapse, and pool saturation.

## Repeatable performance scenarios and evidence

Run:

```bash
./gradlew test --tests '*RoutePlannerSloTest' --info
```

The scenarios use deterministic fixed matrices, warm the JVM, assert identical results each sample,
and exclude network variability so algorithm regressions are repeatable. On 2026-08-23, Java 24,
this workstation measured:

| Scenario | Samples | p50 | p95 | Budget |
| --- | ---: | ---: | ---: | ---: |
| Exact Held-Karp, 12 stops | 20 | 12.6 ms | 19.5 ms | p95 <= 500 ms |
| Greedy + 2-opt, 25 stops | 50 | 0.2 ms | 1.7 ms | p95 <= 100 ms |

These results support the in-process planner, not internet-provider latency. For production evidence,
measure warm-cache and cache-miss endpoint traffic separately and tag only provider/mode/outcome.
The principal cold-path bottleneck is external routing (5 s connect / 12 s read timeout for OSRM),
followed by the exact solver. The hot-path bottleneck is normally DTO/timeline construction; route
and catalog caches should remove provider and search-query work. Re-run scenarios after JVM, solver,
provider, or stop-limit changes and retain the raw CI test output with the release artifact.

## Incident workflow

1. Start from the caller's response `X-Correlation-ID` and locate its completion event.
2. Check status and duration, then compare HTTP, planner, provider, cache, datasource, and JVM panels.
3. If provider latency/errors rose, confirm fallback and cache hit ratio before changing timeouts.
4. If planner latency alone rose, reproduce with `RoutePlannerSloTest` and compare p50/p95.
5. If all endpoints slowed, inspect GC, heap, CPU, database pool, and thread saturation.
6. Preserve correlation IDs and metric snapshots in the incident timeline; never paste tokens or
   request bodies into tickets.
