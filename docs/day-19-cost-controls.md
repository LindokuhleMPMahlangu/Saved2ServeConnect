# Day 19: Budgets, usage limits, and cost visibility

## Account-level monthly budget

`infra/cost-controls.yaml` creates an account-wide AWS Budgets monthly cost
budget and a dedicated SNS topic. The default threshold is **USD 50/month**;
this is an MVP starting value, not a user-approved financial cap. Select an
appropriate value for the target account before deployment. Notifications
are configured at 80% actual spend, 100% actual spend, and 100% forecasted
spend. AWS Budgets notifications are delayed estimates and do not stop or
limit service usage.

This is an account-level budget: costs from other workloads in the same AWS
account contribute to it. Prefer a dedicated MVP account. If the account is
shared, set a threshold that accounts for all workloads and review the
account-wide impact rather than interpreting it as Save2Serve-only spend.

AWS Budgets is provisioned separately in **US East (N. Virginia), `us-east-1`**.
The budget service and its SNS topic must be created in that billing region,
regardless of the application stack's deployment region.

1. Review the proposed cost-controls stack and choose the monthly USD
   threshold. Use a stack name such as `saved2serve-cost-controls`.
2. In the CloudFormation console, select `us-east-1`, create a stack from
   `infra/cost-controls.yaml`, and set `MonthlyBudgetAmount`. Review the
   change set before execution; confirm it creates the intended monthly cost
   budget, SNS topic, and budget-only publish policy.
3. After creation, copy the `CostAlertsTopicArn` output. Subscribe the
   approved finance/on-call notification endpoint using the SNS console,
   confirm the subscription, and send a controlled test notification.
   Restrict topic subscription/management to trusted operators.
4. Open AWS Billing and Cost Management. Confirm the budget is visible and
   notifications are associated with the output topic. Use Cost Explorer to
   inspect costs by service, region, and time range; activate the `Application`
   user-defined cost allocation tag if tag-based filtering is needed. Cost
   allocation tags apply to future usage after activation and can take time
   to appear in Cost Explorer.
5. Review the budget and Cost Explorer monthly. Adjust the budget threshold
   through a reviewed CloudFormation change set when actual usage, account
   sharing, or the approved MVP plan changes.

The topic policy allows `budgets.amazonaws.com` to publish only for the
budget in this stack and account. No email addresses, personal data,
credentials, or application secret values are stored in the template.

## Usage controls

The application stack's HTTP API already throttles traffic at **25 requests
per second with a burst limit of 50**. The follow-up worker has a configurable
reserved-concurrency and SQS event-source maximum-concurrency limit, default
**2**, with an allowed range of 1–10. This caps simultaneous worker executions
and protects downstream SES/DynamoDB usage. Increase it only after reviewing
queue age, retries, SES sending quotas, and the cost impact. A lower limit can
increase delivery delay; a higher limit increases possible concurrent usage.

Additional existing bounded controls include Lambda timeouts, bounded SQS
message retention and redrive to a DLQ after five receives, SQS batch size 10,
on-demand DynamoDB billing, and HTTPS API request throttles. The stack does
not configure an automatic shutoff: blocking requests or disabling delivery
when the budget threshold is reached could affect user submissions and
consented follow-ups.

## Operational response

When a budget notification arrives:

1. Verify the account, budget name, notification type, threshold, and month in
   AWS Billing and Cost Management. Treat forecast alerts as estimates.
2. Review Cost Explorer by service and region. Compare API Gateway requests,
   Lambda invocations/duration/errors, DynamoDB usage, and SQS queue depth
   against the operations dashboard. Do not copy customer data into cost
   reports or tickets.
3. Check whether activity is expected. If usage is unexpected, investigate
   throttling, retries, queue backlog, and deployment changes. Apply any
   response through an authorized change process; do not delete data or
   silently disable user-facing flows.
4. Record the monthly spend, forecast, cause, and approved corrective action.
   Revisit the threshold and concurrency only through reviewed configuration
   changes.

For API health alarms, queue handling, and privacy-safe incident response,
see [Day 18 observability](./day-18-observability.md). For the protected AWS
deployment workflow and target environment configuration, see
[Day 19 deployment workflow](./day-19-deployment-workflow.md).

## Verification and limitations

Review `infra/cost-controls.yaml` to confirm the amount parameter, monthly
USD currency, alert thresholds, SNS destination, and scoped publish policy.
Review `infra/template.yaml` to confirm HTTP API throttling and that both
worker concurrency limits use `FollowUpMaxConcurrency`. Validate templates
and inspect CloudFormation change sets before deployment.

The budget, SNS subscription, cost-allocation tag activation, and AWS billing
visibility have not been tested against a live account. Actual deployment
requires billing/IAM permissions, an approved threshold, and a confirmed
notification subscription.
