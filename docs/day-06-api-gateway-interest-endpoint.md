# Day 06: API Gateway interest endpoint

## Route

`POST /v1/interests` is handled by `InterestApiHandler`, using the API Gateway HTTP API payload format 2.0 and the existing `RegistrationService`. Configure the Lambda integration for this route and set these Lambda environment variables:

- `SAVED2SERVE_TABLE_NAME`: the configured DynamoDB table
- `SAVED2SERVE_KMS_KEY_ID`: the symmetric KMS key used to encrypt email addresses

The AWS SDK default region and credential provider chain are used. The function role needs only the DynamoDB and KMS permissions required by the existing repository.

## Request

Send `Content-Type: application/json`. The UTF-8 request body is limited to 16 KiB, including when API Gateway marks it as base64 encoded.

```http
POST /v1/interests
Content-Type: application/json
```

```json
{
  "idempotencyKey": "signup-attempt-123",
  "name": "Alex Example",
  "email": "alex@example.org",
  "areaCode": "10001",
  "phone": "5550100100",
  "consent": true,
  "source": "landing-page"
}
```

`idempotencyKey`, `name`, `email`, `areaCode`, and `consent` are required. `phone` and `source` are optional strings. Consent must be `true`. If supplied, `source` must be one of `landing-page`, `qr-code`, or `campaign-qr:community-outreach`; an omitted/empty source remains accepted. Unknown properties, non-string text fields, non-boolean consent, malformed JSON, invalid source values, and invalid required values are rejected. Full addresses and precise coordinates are not accepted.

## Responses

| Status | Meaning |
| --- | --- |
| `201` | Registration accepted; an idempotent retry of the same request is also accepted. |
| `400` | Invalid JSON, field types, unknown fields, or required values. |
| `404` | Path does not match this handler. |
| `405` | Method is not POST. |
| `409` | Duplicate registration or idempotency-key conflict. |
| `413` | Request body exceeds 16 KiB. |
| `415` | Content type is not `application/json`. |
| `429` | DynamoDB reports a recognized throttling error; retry later. |
| `503` | Persistence failure; the response contains no internal error details. |

Success response:

```json
{"status":"accepted"}
```

Error responses have `error` and generic `message` fields. All Lambda responses use JSON and `Cache-Control: no-store`; successful responses do not echo submitted fields. Configure API Gateway route/stage throttling separately to enforce request-rate and burst limits at the edge; requests rejected by API Gateway itself do not invoke this Lambda and are returned by the gateway as `429`.

## Privacy and diagnostics

The handler does not log request data, identifiers, or exception messages. Throttling and persistence failures produce only fixed, non-sensitive log messages and generic response bodies. The existing registration service hashes lookup/idempotency data and the DynamoDB mapper encrypts email; do not add API Gateway access-log formats that record request bodies, authorization headers, or query-string PII.

## Verification

`InterestApiHandlerTest` covers accepted requests, base64 payloads, malformed and disallowed input, content type and size limits, duplicate conflicts, throttling, generic persistence failures, and unsupported method/path handling without contacting AWS.
