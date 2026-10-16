# Day 09 - Engagement creation and persistence flow

## Route and request

`POST /v1/engagements` accepts a consented help request, suggestion, or
testimony as JSON. The route is implemented by `EngagementApiHandler`; it uses
`EngagementService` and `EngagementRepository` to validate and persist the
submission.

```http
POST /v1/engagements
Content-Type: application/json
```

```json
{
  "name": "Alex Example",
  "email": "alex@example.org",
  "areaCode": "1800",
  "phone": "5550100100",
  "consent": true,
  "type": "HELP_REQUEST",
  "message": "Please contact me about local support."
}
```

`name`, `email`, `areaCode`, `consent`, `type`, and `message` are required;
`phone` is optional. `consent` must be `true`, and `type` must be one of
`HELP_REQUEST`, `SUGGESTION`, or `TESTIMONY`. The request field allowlist and
Day 09 character limits are applied before service execution. Full addresses,
coordinates, status values, and all other unrecognized fields are rejected.
The body is limited to 16 KiB, including base64-encoded API Gateway payloads.

## Creation and persistence

The service validates the request and creates an `Engagement` with
`PENDING_REVIEW` status. Repository and mapper writes reject records in any
other status, so the submission path cannot publish reviewed content. The
DynamoDB mapper stores the engagement entity type, consent, area code,
engagement type, and pending-review status/index keys. Email is encrypted with
the configured KMS key and encryption context; only its hash is used for the
email lookup index. Name, phone, and message are encoded together and encrypted
with the same KMS key using an engagement-specific encryption context. These
fields are not stored as plaintext DynamoDB attributes. Existing records with
plaintext name, phone, and message attributes remain readable for compatibility;
new submissions use the encrypted attribute.

The Lambda requires `SAVED2SERVE_TABLE_NAME` and `SAVED2SERVE_KMS_KEY_ID`.
Configure API Gateway throttling and TLS at the edge. The handler does not log
or echo submitted fields and returns `Cache-Control: no-store`.

## Responses and privacy boundary

| Status | Meaning |
| --- | --- |
| `201` | Submission accepted; response is `{"status":"accepted"}`. |
| `400` | Invalid JSON, unknown fields, invalid values, or missing consent. |
| `404` | Path does not match the route. |
| `405` | Method is not POST. |
| `413` | Request body exceeds 16 KiB. |
| `415` | Content type is not `application/json`. |
| `429` | DynamoDB reports a recognized throttling error. |
| `503` | Persistence failed; response contains no internal error details. |

Submissions remain private and pending review. This route does not expose a
review queue or public submission data; leader-only review routes remain a
separate authenticated workflow. No submission is auto-approved or
auto-published.

The database mapper enforces the same `PENDING_REVIEW` boundary as the
repository. A future moderation workflow must require authenticated,
authorized leader access; it must not publish content automatically. No
retention duration or TTL is configured here; define and approve a retention
period before adding automatic deletion.

## Review evidence

`EngagementServiceTest` verifies pending-review creation, validation before
persistence, and generic persistence errors. `EngagementApiHandlerTest` covers
accepted requests, base64 payloads, invalid and disallowed input, request
limits, route handling, non-disclosing responses, and persistence failures.
`DynamoDbSaved2ServeRepositoryTest` verifies pending-only writes and
encrypted-email, encrypted-content, and index attributes.

Run the focused tests with:

```powershell
mvn "-DargLine=-Dnet.bytebuddy.experimental=true" "-Dtest=EngagementServiceTest,EngagementApiHandlerTest,DynamoDbSaved2ServeRepositoryTest" test
```
