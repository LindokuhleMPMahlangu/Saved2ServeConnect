# Day 19: Reviewed AWS SAM deployment workflow

## Target and safeguards

`.github/workflows/aws-sam-deploy.yml` prepares and deploys the existing SAM
stack to the AWS account and region configured in the GitHub `production`
environment. This repository does not contain an AWS account ID, region,
role ARN, browser URLs, or verified SES sender; configure the target values in
the repository's protected GitHub environment before the workflow can run.
No deployment credentials or application secrets are committed to the repo.

The workflow is manually dispatched from `main` and has two phases:

1. Run Maven tests, validate and build the SAM template, then use GitHub OIDC
   to prepare a CloudFormation change set without executing it. Review the
   resource/action/replacement summary in the Actions run and the full change
   set in CloudFormation.
2. Dispatch the same workflow again from the same `main` commit, supplying
   the reviewed change-set ARN and the exact validated commit SHA. The
   production GitHub environment approval runs before AWS credentials are
   assumed. The workflow verifies the AWS account, stack, change-set state,
   and commit before execution, then waits for the CloudFormation create or
   update to complete.
3. After successful execution, run non-mutating API smoke checks against the
   deployed stack output. The smoke job checks public devotion and church
   reads, confirms the leader queue rejects an unauthenticated request, and
   verifies that a current authorized leader token can read the queue. It
   captures a minimal deployment evidence JSON artifact even when a smoke
   check fails.

If `main` advances between review and execution, the SHA check rejects the
old change set; prepare and review a new one. A run with no changes reports
that no change set was prepared.

## GitHub environment configuration

Create a GitHub environment named `production`. Restrict its deployment
branch policy to `main` and require an authorized reviewer before allowing a
deployment job to access AWS. Configure these **environment variables**:

| Variable | Purpose |
| --- | --- |
| `AWS_DEPLOY_ROLE_ARN` | IAM role ARN for the GitHub OIDC deployment role. |
| `AWS_ACCOUNT_ID` | Expected 12-digit target AWS account; checked after role assumption. |
| `AWS_REGION` | Region containing the stack and verified SES identity. |
| `SAM_STACK_NAME` | CloudFormation stack name; maximum 39 characters due to explicit Lambda names. |
| `API_STAGE_NAME` | API Gateway stage name, for example `prod`. |
| `ALLOWED_WEB_ORIGIN` | Exact HTTPS frontend origin, without path or trailing slash. |
| `COGNITO_DOMAIN_PREFIX` | Globally unique lowercase Cognito hosted-domain prefix. |
| `COGNITO_CALLBACK_URLS` | Comma-separated exact HTTPS callback URLs; no URL may contain a comma. |
| `COGNITO_LOGOUT_URLS` | Comma-separated exact HTTPS logout URLs; no URL may contain a comma. |
| `SES_SENDER_EMAIL` | Sender identity already verified and approved in SES in the target region. |
| `UNSUBSCRIBE_PAGE_URL` | HTTPS unsubscribe confirmation page URL. |
| `FOLLOW_UP_MAX_CONCURRENCY` | Optional worker and queue-poller limit, integer 1–10; defaults to 2. |

These values configure the intended deployment, not credentials. The template
generates its unsubscribe signing secret in Secrets Manager; do not add it to
GitHub variables or secrets. Add `SMOKE_TEST_LEADER_ACCESS_TOKEN` as a
**production environment secret** containing a current, short-lived Cognito
access token for an administratively provisioned `Leaders` user with the
`saved2serve/leader.read` scope. Refresh it before running the execute phase;
the smoke job fails closed if it is expired or missing the expected
authorization. Never use a real user's long-lived credentials or print the
token in workflow output.

## AWS OIDC role requirements

Configure the IAM role's GitHub OIDC provider as `token.actions.githubusercontent.com`
with audience `sts.amazonaws.com`. Restrict the trust policy to this
repository and the `production` environment subject:

`repo:LindokuhleMPMahlangu/Saved2ServeConnect:environment:production`

The role needs the stack-scoped CloudFormation lifecycle permissions,
permission to publish SAM build artifacts to the deployment bucket, and
`iam:PassRole` only for Lambda execution roles created by this stack. Scope
resource permissions to the target account, region, stack, and resource set
where supported; do not grant administrator access or wildcard PassRole.
Review the effective permissions and template change set before executing.
See [Day 17 infrastructure guidance](./day-17-aws-infrastructure.md#deployment-permission-review).

## Running and reviewing a deployment

1. Confirm the `production` GitHub environment settings and variable values,
   the OIDC trust policy, deployment-role permissions, SES identity, and
   reviewed CloudFormation resource plan.
2. In GitHub Actions, run **AWS SAM Deployment** on `main` with both inputs
   blank. Wait for Maven, `sam validate --lint`, and `sam build` to pass.
3. Review the run summary and the complete CloudFormation change set. Reject
   unexpected resource replacement or destructive actions. Copy the exact
   change-set ARN and validated commit SHA from the summary.
4. Dispatch the workflow again on the same `main` commit. Supply both the
   change-set ARN and the exact reviewed commit SHA. Obtain the required
   `production` environment approval; the job checks the AWS account, stack,
   and change-set state before execution and waits for stack completion.
5. Confirm the post-deployment smoke-test job passes. It performs only GET
   requests: public `GET /v1/devotions`, public
   `GET /v1/churches?areaCode=1800`, unauthenticated
   `GET /v1/admin/engagements` (expected 401/403), and authenticated
   `GET /v1/admin/engagements` (expected 200 with a `Leaders` group token
   carrying `saved2serve/leader.read`). Public response records are also
   checked against their approved public-field allowlists. It does not submit,
   review, or alter personal data.
6. Run the remaining Day 17 post-deployment checks for Cognito, IAM, logs,
   queues, and retention. Configure and test the Day 18 SNS alert subscription
   separately. Deploy the standalone billing-region budget and confirm its
   alert subscription using [Day 19 cost controls](./day-19-cost-controls.md).

The workflow does not deploy from pull requests, run on every push, print
request/submission data, or place credentials or the generated signing secret
in repository files, job outputs, or command-line parameters.

## Verification evidence and limitations

The existing Java quality workflow remains unchanged. The deployment
workflow independently runs the complete Maven test suite and SAM validation
and build, plus offline unit tests for the smoke-check and evidence-capture
scripts, before any AWS credentials are requested. The smoke checks run only
after a reviewed change set has executed and CloudFormation reports stack
completion. Review the workflow YAML and GitHub Actions run logs as evidence;
a successful build does not prove that GitHub environment settings, AWS OIDC
trust, IAM permissions, or target account resources are correct.

No AWS environment was provided in this checkout, so the workflow has not
been dispatched and no AWS resources have been created or modified.

## Rollback and recovery

Treat infrastructure rollback and application-data recovery as separate
operations. The DynamoDB table and KMS key have `Retain` deletion and
replacement policies; DynamoDB point-in-time recovery is enabled. CloudFormation
rollback does not roll back customer records, undo messages already sent, or
reverse external effects.

### A deployment fails before stack update completes

1. Stop further deployment attempts. Inspect CloudFormation stack events and
   status in the configured account/region to determine whether CloudFormation
   completed an automatic rollback.
2. If an update ends in `UPDATE_ROLLBACK_COMPLETE`, preserve that known-good
   state and review the failed resource/event. Do not retry the same change
   set; correct the cause, then prepare a fresh change set from the current
   `main` commit and review it before execution.
3. If rollback itself ends in `UPDATE_ROLLBACK_FAILED`, do not delete the
   stack or skip resources as a shortcut. Escalate to an authorized AWS
   operator to inspect the failed resources and follow the CloudFormation
   `continue-update-rollback` recovery procedure for the exact stack. Record
   any resource skipped and reconcile it before another deployment.
4. For an initial create that fails, confirm the stack reaches a terminal
   rollback state before retrying. Check retained resources: a table or key
   retained during rollback may still exist and may need an explicitly
   reviewed import/reuse plan.

### A deployment succeeds but smoke checks fail

1. Treat the smoke-test failure as a production verification failure, not
   proof that the stack update rolled back. Preserve the failed run's
   evidence artifact and inspect only its deployment metadata and sanitized
   smoke output.
2. Check public route response shapes, API URL/stage, Cognito scopes/group,
   deployment change-set review, and CloudFormation status. Do not include
   access tokens, request/response bodies, user data, or queue payloads in
   incident records.
3. Prefer a reviewed forward fix when it is safe. If rollback is required,
   create and merge a revert of the deployed code/infrastructure change to
   `main`, then run the normal workflow to create and review a **new**
   change set. Execute that change set only from the exact commit used to
   prepare it. Do not attempt to execute an old change set or redeploy an
   arbitrary historical commit outside the protected workflow.
4. Wait for CloudFormation to finish and rerun the public/authenticated
   smoke checks. Check the operations dashboard and queue/DLQ state. Account
   for any emails already sent; an infrastructure revert cannot recall them.

### Persistent data and retained resources

- Never delete/recreate the retained DynamoDB table or KMS key as a rollback
  shortcut. Review CloudFormation replacements carefully; both resources are
  retained on deletion and replacement, so an old physical resource can
  remain while the stack points elsewhere.
- A reverted application version may be incompatible with records written by
  the newer version. Check mapper/schema compatibility before reverting
  application code. If records require recovery, use the approved DynamoDB
  point-in-time restore procedure to a separately named table, validate it,
  and plan a controlled cutover; do not restore over the live table without
  an approved recovery plan.
- Do not replay follow-up queue messages blindly. Inspect retries/DLQ through
  an authorized workflow and prevent duplicate emails before redrive.
- The monthly cost budget is deployed separately in `us-east-1`. Rolling back
  the application stack does not remove or change the budget stack or its
  SNS subscription. Keep cost notifications active during incident recovery;
  budget alerts are not a spending cap.

### Deployment evidence

After a reviewed change set executes and stack status is available, the
workflow produces `deployment-evidence/evidence.json` as the
`saved2serve-deployment-evidence-<run>-<attempt>` Actions artifact, retained
for 30 days. It records repository/commit, workflow run identifiers, stack
name/region/status, stack update time, change-set ARN, record timestamp, and
smoke-test step outcome. The artifact deliberately excludes CloudFormation
parameters and outputs, resource physical IDs, event reason strings, HTTP
request/response data, access tokens, and personal data.

If the smoke check fails, `if: always()` still captures and uploads the
deployment metadata and failure outcome. Preserve the failed Actions run and
evidence artifact with the incident record; keep access limited to authorized
maintainers because infrastructure identifiers are operationally sensitive.
If the workflow is cancelled before evidence capture, record the run URL,
commit, stack/region, change-set ARN, CloudFormation status/events, and
operator actions in the approved incident/change system without copying
personal or secret data.
