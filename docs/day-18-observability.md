# Day 18: Privacy-conscious operational observability

## Structured application logs

Application error and throttling events use `OperationalLog` and are emitted as
JSON records with a UTC timestamp, severity, fixed event name, and explicitly
approved operational fields. Unrecognized fields are omitted. Known personal
and secret fields (including names, email, phone, record IDs, request bodies,
tokens, and authorization values) are replaced with `[REDACTED]`. Do not pass
request objects, exception messages, queue payloads, or free text to the
logger.

The follow-up worker emits `FOLLOW_UP_BATCH_PROCESSED` with batch, success,
and failure counts. It does not log message IDs, record IDs, recipient
addresses, or message bodies. API Gateway access logs remain limited to
request ID, route key, status, and response length. Application and API
access log groups retain logs for 30 days.

## Metrics and dashboard

The `OperationsDashboardName` stack output identifies a CloudWatch dashboard
covering:

- API Gateway request counts and 4XX/5XX responses.
- Lambda errors and throttles for the API handlers and follow-up worker.
- Follow-up SQS messages sent, received, and deleted.
- Follow-up queue oldest-message age and visible DLQ messages.

These are AWS-managed `AWS/ApiGateway`, `AWS/Lambda`, and `AWS/SQS` metrics;
the dashboard does not add application-level metrics containing customer or
submission data. The follow-up handler's structured batch counts help
correlate SQS processing without exposing message identifiers.

## Alarms and notification setup

The stack creates alarms for API 5XX responses, errors from the interest,
engagement, and follow-up Lambda functions, follow-up queue age (15 minutes),
and any visible dead-letter message. The alarms publish to the
`OperationalAlertsTopicArn` stack output. Subscribe the on-call operational
notification endpoint to that SNS topic after deployment, confirm the
subscription, and test delivery through the approved notification process.
Do not put recipient or submission data in alarm names, SNS messages, or
dashboard labels.

Missing metrics are treated as not breaching to avoid false alarms before a
function receives traffic. The dashboard and alarm thresholds are initial MVP
defaults: review them against observed, non-personal operational traffic
before production use.

## Incident response

1. **Acknowledge and scope.** Record the alarm name, account, region, stack,
   UTC onset, and affected API/queue. Use the request ID or CloudWatch
   invocation metadata for correlation; never copy request bodies, tokens,
   email addresses, names, or queue payloads into incident notes.
2. **API 5XX or Lambda errors.** Check the operations dashboard and the
   matching function's structured error events. Compare API request counts,
   5XX counts, Lambda errors, and throttles. Check the service health and
   recent reviewed deployment change set. Avoid logging raw exception
   messages or customer-supplied values while diagnosing.
3. **Queue age or DLQ alarm.** Compare sent/received/deleted counts, oldest
   message age, worker errors, and DLQ depth. Check SQS redrive count, SES
   service/identity status, and worker permissions. Preserve partial batch
   failure behavior; do not purge the queue or blindly redrive messages.
4. **Contain and recover.** If delivery must be paused, use the approved
   operator change process to disable the event source mapping or stop
   enqueueing, recording the action and owner. Correct the identified cause,
   then resume in a controlled manner. Review a small DLQ sample only through
   an authorized workflow that protects personal data; do not copy message
   contents into logs or tickets.
5. **Verify and close.** Confirm errors and queue age return to normal, check
   DLQ depth and partial-batch retries, verify notification delivery, and
   document impact, timeline, root cause, and preventive action with
   identifiers removed. Escalate any suspected personal-data exposure using
   the organization's privacy/security incident process.

Do not delete application records, change retention, decrypt records, or
redrive user notifications as part of routine alarm handling without an
explicitly approved operational procedure.

## Verification evidence

Automated coverage in `OperationalLogTest` verifies JSON output, allowed
operational fields, explicit redaction, and omission of unknown/free-text
fields. `FollowUpDeliveryHandlerTest` verifies partial batch failures still
return only failed message IDs for SQS retry and that the emitted batch event
contains counts but no recipient or record identifiers.

Before deployment, validate and build the SAM template and run the Maven
tests. After deployment, confirm the dashboard loads, each alarm is in the
expected state, the SNS subscription is confirmed, and AWS-managed metrics
appear after traffic. Use a controlled test environment to exercise an API
failure and a partial SQS batch failure; verify alarms and logs without
submitting real personal data. No AWS deployment or live CloudWatch/SNS
verification is performed by these repository tests.
