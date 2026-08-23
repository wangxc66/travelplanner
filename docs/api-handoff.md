# API Week 4 integration handoff

This note is for frontend developers and reviewers using the backend locally. The
machine-readable contract remains [openapi.yaml](openapi.yaml); this is the shortest
repeatable development flow.

## Start and verify

```powershell
.\gradlew.bat build
.\gradlew.bat bootRun
```

The local server listens on `http://localhost:8080`. It starts with an in-memory,
seeded city and POI catalog. Accounts and trips are intentionally not seeded.

## Happy-path example

1. Register an account with `POST /auth/register`:

   ```json
   {
     "username": "local_api_tester",
     "password": "Test123456",
     "displayName": "Local API Tester"
   }
   ```

2. Copy `token` from the response and send it on protected endpoints:

   ```http
   Authorization: Bearer <token>
   ```

3. Call public `GET /api/cities`, copy a returned city `id`, then create a trip:

   ```json
   {
     "cityId": 1,
     "title": "Weekend in Tokyo",
     "startDate": "2026-09-01",
     "numDays": 2
   }
   ```

4. Use the returned trip `id` with `GET /api/trips/{tripId}`. All mutating trip
   endpoints return the full, recomputed `TripDto` except trip deletion.

## Error handling contract

Every documented client error is JSON with these fields:

```json
{
  "message": "English fallback text",
  "code": "error.semanticCode",
  "params": {}
}
```

Frontend code should branch on `code`, not on the English `message`.

| Situation | HTTP status | Example code |
| --- | --- | --- |
| Missing or invalid JWT | `401` | `error.signInRequired` |
| Valid user, but operation is forbidden | `403` | `error.forbidden` |
| Invalid JSON, request body, query, or path value | `400` | `error.invalidRequest` |
| User cannot see the requested trip or item | `404` | `error.tripNotFound` / `error.itemNotFound` |
| Duplicate username or POI in one trip | `409` | `error.usernameTaken` / `error.poiAlreadyPlanned` |

For privacy, accessing another user's trip returns `404 error.tripNotFound`; it must
not reveal whether that trip id exists.

## API decisions requiring coordinated changes

- Current create endpoints return `200` and trip deletion returns an empty `200`.
  Changing these to `201` / `204` affects frontend behavior and must be approved by
  the API contract owners before implementation.
- `POST /items/{itemId}/lock` is a legacy toggle and is not idempotent. A future
  idempotent replacement should be `PUT` with an explicit `locked: true|false` body.
- Do not commit JWTs, provider keys, `.env` files, or local IDE configuration.
