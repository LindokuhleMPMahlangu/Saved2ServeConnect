# Architecture

## Overview

Saved2ServeConnect is designed as a serverless, privacy-conscious web platform for church discovery and engagement. The MVP keeps the public surface minimal while protecting admin-only workflows behind Cognito JWT authentication.

The core topology is:

- Public users reach a CloudFront distribution in front of a private S3 bucket using Origin Access Control (OAC).
- API Gateway HTTP API exposes a small set of JSON endpoints for public discovery and protected admin actions.
- Java 21 Lambda functions implement the application logic and interact with DynamoDB.
- DynamoDB stores registration, church, devotion, and engagement records.
- Cognito issues and validates JWTs for admin-only access.
- SQS with a dead-letter queue (DLQ) decouples asynchronous processing and retries for non-critical workflows.
- SES sends notification emails such as admin alerts and user confirmations.
- CloudWatch provides logging, metrics, alarms, and operational visibility.

## Topology

```text
Internet
  |
  v
CloudFront (HTTPS)
  |
  +--> Private S3 bucket via OAC (static web assets only)
  |
  +--> API Gateway HTTP API
            |
            +--> Java 21 Lambda functions
                        |
                        +--> DynamoDB
                        |
                        +--> SQS --> DLQ + retry handling
                        |
                        +--> SES for notifications
                        |
                        +--> CloudWatch Logs / Metrics / Alarms

Admin portal access:
  Cognito JWT validation -> API Gateway / Lambda authorizer -> protected admin routes
```

## API routes

The public and private routes are intentionally small and explicit:

- POST /v1/interests
- GET /v1/churches
- GET /v1/devotions
- POST /v1/engagements
- GET /v1/admin/engagements
- PATCH /v1/admin/engagements/{id}

### Route intent

- POST /v1/interests: create a visitor interest or registration record, limited to consented, voluntary submission.
- GET /v1/churches: return approved churches for an exact active area-code match only; do not infer nearby or broader matches.
- GET /v1/devotions: return approved devotion links only when they meet the allowed scheme and platform rules. The public response includes only `devotionId`, `title`, `url`, and `platform`; source and review metadata remain internal.
- POST /v1/engagements: create leader help requests, suggestions, or testimony submissions in a review state.
- GET /v1/admin/engagements: list submissions requiring admin review.
- PATCH /v1/admin/engagements/{id}: update submission status such as PENDING_REVIEW, approved, or rejected.

### JSON contract expectations

Public JSON payloads are intentionally lean. Inputs should be validated for required fields and constrained to approved data types. Admin endpoints must require a valid Cognito JWT and should not expose sensitive or unpublished records to anonymous callers.

## Data boundaries and exclusions

### Included data

The MVP should collect only the minimum required records necessary for church matching and engagement support:

- name
- email
- optional phone
- area code
- consent flags

This includes the minimum data needed to:

- confirm a voluntary registration
- match a visitor to approved churches by area code
- contact a person if necessary for follow-up or admin review
- respect consent preferences for notifications and data handling

### Explicit exclusions

The system must not collect or require any of the following:

- street addresses
- full residential address details
- GPS coordinates
- exact location coordinates
- automatic publishing of testimonies
- admin-only data in public APIs

The platform must not infer or store precise home location data, and all location matching must remain area-code based and privacy-preserving. The approved directory and exact-match rules are defined in [Day 07 - Approved church directory and service-area data](day-07-approved-church-directory-and-service-areas.md).

## Privacy and security checklist

### Threat and privacy checklist

- Encryption in transit: enforce HTTPS with CloudFront and API Gateway TLS endpoints.
- Encryption at rest: use DynamoDB, S3, and related AWS-managed encryption features with appropriate KMS controls.
- Least privilege: allocate IAM roles and policies with the minimum required permissions for Lambda, S3, SQS, SES, and DynamoDB access.
- Log redaction: redact personal data, email addresses, consent details, and any human identifiers from logs before they are stored or viewed.
- CORS: restrict allowed origins to known frontend hosts and avoid permissive wildcard rules for authenticated or admin routes.
- Input validation: validate required fields, email format, consent flags, and route parameters before persistence.
- Queue safety: use an SQS DLQ for failed async tasks and monitor retry loops to avoid silent message loss.
- Email controls: ensure SES notifications only send to approved recipients and avoid exposing internal data in notification content.
- Cognito authorization: require valid Cognito JWT verification on admin-only routes and reject expired or malformed tokens.
- Data minimization: store only required fields and avoid data retention beyond the business need.
- Public API boundary: keep user-submitted testimonies, help requests, and suggestions out of public APIs until reviewed and approved.

## Operational notes

- CloudFront serves the static frontend from a private S3 bucket behind OAC, reducing direct public S3 exposure.
- API Gateway HTTP API handles public JSON requests and passes to Java 21 Lambda functions.
- Lambda functions perform request validation, database updates, and route-specific authorization decisions.
- SQS handles asynchronous operations such as delayed or retryable processing; DLQs provide visibility into failures.
- SES sends operational notifications and user confirmations without broad application-level exposure.
- CloudWatch captures access logs, Lambda errors, queue depth, SES metrics, and alarm states for operations and incident response.

## Security posture summary

This architecture balances a lightweight public experience with strict governance around admin data, location privacy, and consent. The design keeps location exposure to area-code level only, ensures admin routes are protected by Cognito JWT validation, and prevents unpublished engagement submissions from being exposed through public APIs.
