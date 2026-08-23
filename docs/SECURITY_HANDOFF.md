# Authentication and security handoff

## Authentication contract

- `POST /auth/register` accepts a 3–64 character username containing letters, digits, `.`, `_`, or
  `-`; usernames are trimmed and normalized with `Locale.ROOT` lower-case.
- Passwords must contain at least 12 characters and at most 72 UTF-8 bytes. The byte limit prevents
  BCrypt's silent 72-byte truncation from making distinct long passwords equivalent.
- Passwords are stored with BCrypt cost 12. Plaintext passwords never enter logs or DTO responses.
- `POST /auth/login` returns the same `401 error.badCredentials` for missing users and wrong
  passwords. A dummy BCrypt comparison reduces username-enumeration timing differences.
- Registration's pre-check provides a friendly error; the database unique constraint and exception
  mapping close concurrent registration races.

JWTs are HMAC-signed and contain subject, issuer, audience, issued-at, expiry, and a unique JWT ID.
Parsing verifies signature, issuer, audience and expiry. The configured TTL must be 1–24 hours and is
12 hours by default. Missing, malformed, tampered, expired, wrong-key, wrong-issuer and wrong-audience
tokens remain anonymous; protected endpoints answer stable JSON with `401 error.signInRequired`.

## Secret deployment requirements

Set `TRAVELPLANNER_JWT_SECRET` to at least 32 cryptographically random bytes (prefer 32 random bytes
base64-encoded) in the deployment secret manager. Never store it in Git, container images, build
logs, frontend configuration, Terraform state output, or support tickets. The `postgres` profile
sets `allow-insecure-secret=false` and refuses to start with the local demo secret.

Rotating the HMAC secret immediately invalidates every outstanding token. Coordinate the rotation,
expect clients to sign in again, and retain no old secret unless an explicit short overlap policy is
implemented. The current baseline has no refresh token, revocation list, key ID, or multi-key overlap.

## Authorization boundary

Public endpoints are registration/login, health, the disabled-by-default H2 console path, and GET
city/category/POI discovery. Trip CRUD, itinerary mutations, optimization, rebalance and metrics are
authenticated. Security uses stateless sessions and CSRF is disabled because authentication is an
explicit bearer header, not an ambient cookie.

Trip service reads and mutations resolve a trip using both `tripId` and authenticated `userId`.
Item lookups are then scoped to that owned trip. Cross-user access deliberately returns the same
`404 error.tripNotFound` as an unknown trip, preventing resource enumeration. The trip pessimistic
lock query includes ownership before acquiring the lock.

## Browser boundary

`CORS_ALLOWED_ORIGINS` is a comma-separated list of exact origins, for example
`https://planner.example.com`. Wildcards fail application startup. Local defaults explicitly allow
the CRA frontend on `localhost`/`127.0.0.1:3000` and optional Vite development on port 5173. Allowed request headers are `Authorization`, `Content-Type`, and
`X-Correlation-ID`; the correlation response header is exposed. Credentials/cookies are disabled,
and preflights cache for one hour. Frame protection is `SAMEORIGIN`, needed only by the local H2
console rather than disabled globally.

Remember that CORS is a browser control, not API authentication. Non-browser callers can ignore it.

## Abuse and rate-limit boundary

Login and registration allow 10 attempts per 60-second fixed window per direct socket address,
returning `429 error.rateLimited` and `Retry-After` beyond the boundary. The in-memory map is bounded
and exports `travelplanner.auth.rate_limit.rejected`.

This is per application instance and intentionally ignores untrusted `X-Forwarded-For`. A production
cluster must enforce a shared policy at the ingress/API gateway using its trusted client-IP parsing,
then retain the application filter as defense in depth. Consider separate login/registration limits,
account-aware controls after privacy review, progressive delay, and credential-stuffing detection.

## Operations checklist

1. Supply a random JWT secret and exact HTTPS CORS origins; start with the `postgres` profile and
   confirm the demo-secret guard does not fire.
2. Ensure TLS terminates at a trusted proxy and that HTTP cannot reach the application externally.
3. Restrict `/actuator/metrics` and `/actuator/prometheus` to the monitoring network/authentication.
4. Configure the gateway rate limit and trusted proxy headers; do not blindly trust forwarded IPs.
5. Alert on authentication 401/429 rates without logging usernames, passwords, tokens, or bodies.
6. Run `./gradlew test` and specifically retain `SecurityBoundaryIntegrationTest`, `JwtServiceTest`,
   `AuthRateLimitFilterTest`, `SecurityConfigTest`, and `TripDomainIntegrationTest` results.
7. Re-review dependencies, token lifetime, BCrypt cost and password policy before production launch.

Residual baseline limitations: no MFA, email verification, password reset, token revocation, refresh
tokens, compromised-password screening, account lockout, or shared application-level rate-limit
store. Those require product and operational workflows rather than hidden behavior in this service.
