# Day 11: Leader engagement queue and review workflow

## Scope

The leader-only API supports bounded engagement queue reads and a one-way
review action. Public engagement submission remains unchanged: new submissions
are consented, encrypted, and stored as `PENDING_REVIEW`; they are not
published by approval.

## Queue

`GET /v1/admin/engagements` requires the `saved2serve/leader.read` scope and
the exact Cognito `Leaders` group, checked by `LeaderAuthorization` in the
Lambda. The route must also use the API Gateway HTTP API JWT authorizer as
described in [day-10-cognito-jwt-authorizer.md](./day-10-cognito-jwt-authorizer.md).
No public route should invoke this handler.

Supported query parameters:

| Parameter | Meaning |
| --- | --- |
| `status` | `PENDING_REVIEW`, `APPROVED`, or `REJECTED`; defaults to `PENDING_REVIEW`. |
| `from` | Optional inclusive ISO-8601 UTC instant, for example `2026-10-01T00:00:00Z`. |
| `to` | Optional exclusive ISO-8601 UTC instant. If both bounds are supplied, `from` must precede `to`. |
| `limit` | Page size from 1 through 100; defaults to 25. |
| `cursor` | Opaque continuation value returned by the preceding response. It is validated against the selected status and time bounds. |

The status/time query uses `StatusCreatedAtIndex` and filters to
`entityType = ENGAGEMENT`; results are newest first. The response contains
`items` and `nextCursor`. Each item includes its opaque engagement ID, creation
time, status, type, coarse area code, and consented contact/message fields so
an authorized leader can review it. The endpoint sets `Cache-Control:
no-store`. A cursor can produce a short or empty page while a shared DynamoDB
index page is traversed; clients should continue while `nextCursor` is
non-null.

Index reads are eventually consistent. A just-reviewed item may briefly remain
visible in the previous queue status; the write itself is protected by a
conditional transaction and cannot approve or reject the same pending
submission twice.

## Review action and audit

`PATCH /v1/admin/engagements/{engagementId}` requires
`saved2serve/leader.write`, the `Leaders` group, and a canonical engagement
UUID. The JSON body must contain exactly one field:

```json
{"status":"APPROVED"}
```

The only allowed target states are `APPROVED` and `REJECTED`. The reviewing
identity comes from the verified API Gateway JWT `sub` claim, never from the
request body. A DynamoDB transaction conditionally changes only an
`ENGAGEMENT` whose current status is `PENDING_REVIEW` and writes a companion
`ENGAGEMENT_REVIEW` record in the same transaction. The audit record contains
the engagement ID, previous and new status, reviewer subject, and timestamp;
it does not duplicate contact or message data. Attempts to review an already
handled item return `409 invalid_transition`; missing IDs return `404`.

Reviewing an engagement does not publish it or make it appear in a public API.
Only the status index is changed; the encrypted submission content remains
encrypted at rest.

## Verification

Focused unit coverage is provided by `LeaderEngagementApiHandlerTest`,
`DynamoDbSaved2ServeRepositoryTest`, and `EngagementServiceTest`. These tests
cover leader/scope enforcement, status and time filtering, pagination
parameters, invalid requests, conditional updates, and minimal audit events.
They do not validate a deployed API Gateway, Cognito pool, or live DynamoDB
table. Configure the two routes with the scopes above and keep the public
`POST /v1/engagements` route unauthenticated.

Run the focused tests with:

```powershell
mvn "-DargLine=-Dnet.bytebuddy.experimental=true" "-Dtest=LeaderEngagementApiHandlerTest,DynamoDbSaved2ServeRepositoryTest,EngagementServiceTest" test
```
