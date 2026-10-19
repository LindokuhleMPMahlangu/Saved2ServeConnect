# Day 12: Opt-in follow-up notifications

## Consent and withdrawal

Interest registrations and engagement submissions may include the separate
optional boolean `followUpConsent`. Omitting it means `false`; it is never
inferred from the required consent to submit the record. Non-boolean values
are rejected. A positive choice is stored with `followUpConsentedAt` at
acceptance time. Submission consent and its timestamp remain independent.

An unsubscribe link carries an opaque record UUID, record type, and
HMAC-SHA256 signature bound to both values. The configured URL is a
confirmation page, not a one-click state-changing endpoint: clients must not
withdraw consent on GET because email link scanners may follow links. After
the user confirms, the page POSTs this JSON to `/v1/follow-ups/withdraw`:

```json
{
  "recordId": "11111111-1111-4111-8111-111111111111",
  "recordType": "INTEREST",
  "token": "<signed-token-from-link>"
}
```

The endpoint returns a generic `202` for both valid and invalid signatures,
and checks the signature before accessing DynamoDB. Valid requests conditionally
set `followUpConsent` to false and record `followUpWithdrawnAt`; repeating a
withdrawal has no additional effect. The client should not include or log
names, email addresses, phone numbers, or submitted engagement messages in
the withdrawal request or its own logs.

## Queue and delivery

The public interest and engagement handlers send an SQS message only after the
record has been saved and explicit follow-up consent is true. Queue bodies
contain only the record UUID and type, never contact or submission content.
The worker performs a strongly consistent read immediately before delivery
and suppresses messages if the record is missing or consent has since been
withdrawn. The SES message is generic and does not reproduce a submitted
message, testimony, or other engagement content.

The worker returns per-message SQS batch failures so only failed messages are
retried. Configure the event source mapping with
`ReportBatchItemFailures`, a bounded redrive policy, and a dead-letter queue.
This implementation assumes a standard SQS queue with at-least-once delivery.
If SES accepts an email but the Lambda fails before acknowledging its SQS
message, a retry can send a duplicate. An accepted idempotent interest replay
can also re-enqueue its message, so clients should not treat the API idempotency
key as an exactly-once email guarantee. This is not an exactly-once delivery
system.

## Deployment configuration (not provisioned by this change)

Set these environment variables on the relevant Lambda functions:

| Variable | Used by | Purpose |
| --- | --- | --- |
| `SAVED2SERVE_TABLE_NAME` | API handlers, worker | Existing single-table DynamoDB table. |
| `SAVED2SERVE_KMS_KEY_ID` | API handlers, worker | Key used by the existing encrypted-record adapter. |
| `SAVED2SERVE_FOLLOW_UP_QUEUE_URL` | Interest and engagement APIs | SQS queue URL. |
| `SAVED2SERVE_SES_FROM_ADDRESS` | Delivery worker | Verified SES sender address. |
| `SAVED2SERVE_UNSUBSCRIBE_SIGNING_SECRET` | Worker and withdrawal API | Shared secret of at least 32 UTF-8 bytes; store in a secrets service, not source control. |
| `SAVED2SERVE_UNSUBSCRIBE_URL` | Delivery worker | HTTPS confirmation-page URL that accepts `recordId`, `recordType`, and `token` query parameters. |

Grant least-privilege access: producers need `sqs:SendMessage`; the worker
needs SQS receive/delete/queue-attribute permissions, DynamoDB query access,
KMS decrypt access for opted-in recipient details, and `ses:SendEmail`; the
withdrawal API needs DynamoDB query/update access. The API functions retain
their existing data-write and KMS permissions. SES must be out of the sandbox
or the sender and test recipients must be verified in the configured region.
Do not log request bodies, queue bodies, recipient addresses, message content,
or unsubscribe tokens. Application logs report only generic failure messages.

No AWS queue, event source mapping, dead-letter queue, IAM policy, SES identity,
API route, or Lambda deployment is created or verified by this repository
change.

## Verification

Focused tests cover independent opt-in parsing for both public APIs, consent
timestamps, queue dispatch, the PII-minimal queue payload, current-consent
suppression, signed withdrawal, generic SES content, and per-message SQS
retry responses. Run them with:

```powershell
mvn clean "-DargLine=-Dnet.bytebuddy.experimental=true" "-Dtest=FollowUpDeliveryWorkerTest,FollowUpDeliveryHandlerTest,FollowUpWithdrawalApiHandlerTest,RegistrationServiceTest,EngagementServiceTest,DynamoDbRecordMapperTest,DynamoDbSaved2ServeRepositoryTest,InterestApiHandlerTest,EngagementApiHandlerTest,InterestEngagementRecordsTest" test
```
