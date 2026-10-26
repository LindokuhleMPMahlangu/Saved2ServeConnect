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
   and commit before execution.

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

These values configure the intended deployment, not credentials. The template
generates its unsubscribe signing secret in Secrets Manager; do not add it to
GitHub variables or secrets.

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
   and change-set state before execution.
5. Monitor CloudFormation stack events to completion. Run the Day 17
   post-deployment checks for routes, Cognito, IAM, logs, queues, and
   retention. Configure and test the Day 18 SNS alert subscription separately.

The workflow does not deploy from pull requests, run on every push, print
request/submission data, or place credentials or the generated signing secret
in repository files, job outputs, or command-line parameters.

## Verification evidence and limitations

The existing Java quality workflow remains unchanged. The deployment
workflow independently runs the complete Maven test suite and SAM validation
and build before any AWS credentials are requested. Review the workflow YAML
and GitHub Actions run logs as evidence; a successful build does not prove
that GitHub environment settings, AWS OIDC trust, IAM permissions, or target
account resources are correct.

No AWS environment was provided in this checkout, so the workflow has not
been dispatched and no AWS resources have been created or modified.
