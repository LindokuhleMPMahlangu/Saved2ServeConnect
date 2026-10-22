# Saved2ServeConnect

Saved2ServeConnect is a Java 21 Maven project for the privacy-first MVP foundation of the Save2Serve platform. The repository focuses on a narrow, reviewable baseline for Lambda-based serverless workflows, validation logic, CI quality checks, and consent-aware public submission rules.

> The architecture document remains in its own file under the docs folder and is intentionally not duplicated here.

## Project purpose

The MVP is designed around strict privacy and minimization principles:

- no full residential addresses
- no precise GPS coordinates
- area-code-based matching only
- public submissions remain pending review and are never auto-published
- admin-only routes require authenticated JWT-protected access
- consent is required before registration or content submissions are accepted
- only approved devotion links using HTTPS are accepted

## Day 02 deliverables included

This repository includes the core Day 02 baseline and supporting artifacts:

- Java 21 Maven project with pinned dependencies
- Lambda bootstrap abstraction with credential-free construction for local and test scenarios
- repository ignore rules for build and editor artifacts
- GitHub Actions quality gate for Maven validation
- privacy-aware validation logic and acceptance scenarios
- review, retention, threat, and release decision records

## Day 03 deliverables included

The repository now includes immutable privacy-conscious domain records and explicit request DTOs for public submission flows:

- Interest record: stores voluntary registration intent with consent, area-code matching, and a minimal contact footprint
- Engagement record: stores pending-review leadership submissions while blocking full-address and precise-location data
- InterestRequest and EngagementRequest DTOs define explicit field allowlists and avoid public payload drift beyond the approved MVP schema
- Supporting enums for engagement type and review status keep the model explicit and reviewable

These records intentionally reject full residential addresses, precise coordinates, and non-consented submissions to stay within the Save2Serve MVP boundaries.

## Day 04 deliverables included

This repository now includes the DynamoDB design notes for the Save2Serve MVP access patterns:

- single-table layout for interests and engagement records
- primary keys, GSI definitions, and review-queue access patterns
- area-code-based lookup strategy and email-hash duplicate detection
- domain-oriented `InterestRepository` and `EngagementRepository` contracts with no AWS SDK types
- DynamoDB mapping with KMS-encrypted email and paginated status queue queries
- privacy-preserving design decisions that avoid full address and GPS storage

The complete write-up is in [docs/day-04-dynamodb-keys-indexes-and-access-patterns.md](docs/day-04-dynamodb-keys-indexes-and-access-patterns.md) and stays aligned with the privacy-first MVP boundaries. The interfaces define the AWS-SDK-independent application contract, implemented by a separate DynamoDB/KMS persistence adapter.

## Day 05 deliverables included

The registration application service is covered by deterministic unit tests using injected repository, hashing, and clock dependencies. The service records the consent timestamp at acceptance time; clients cannot supply or backdate it. Repository failures become a typed persistence exception with a generic non-sensitive message and the original cause preserved for trusted diagnostics. Normalized email hashes drive duplicate checks, while callers supply an idempotency key to safely replay the same request after an uncertain response. DynamoDB stores only hashes, fingerprints, consent time, and allowlisted interest fields; conditional writes prevent a retry from creating a second registration, and reusing a key for changed request data is rejected. The flow remains area-code-only and does not collect full addresses or GPS coordinates.

## Day 06 deliverables included

The `POST /v1/interests` API Gateway HTTP API Lambda handler validates JSON content type, allowlisted fields, required values, base64 payloads, and a 16 KiB request limit before delegating to the registration service. It returns explicit validation, conflict, throttling, and generic service-failure responses without logging or echoing personal data. See [docs/day-06-api-gateway-interest-endpoint.md](docs/day-06-api-gateway-interest-endpoint.md) for the request/response contract, deployment environment, and API Gateway rate-limit notes.

## Day 07 deliverables included

The approved church directory and service-area contract is documented in [docs/day-07-approved-church-directory-and-service-areas.md](docs/day-07-approved-church-directory-and-service-areas.md). `GET /v1/churches?areaCode=...` uses the `ChurchDirectoryService` and `ChurchDirectoryRepository` for exact area-code lookup and approved entries only; the DynamoDB adapter filters by directory entity type and approval status, and the public response omits review metadata. `saveApproved` persists one exact church-to-area mapping only for an approved record with a verification timestamp and source reference. The initial pilot seed maps House of Treasures Ministries to postcode `1800`, based on project-owner attestation that the church confirmed that service area. Only that confirmed code is included; additional coverage is not inferred. The seed also includes the owner-approved public church address. Run it with `mvn -DskipTests package exec:java "-Dexec.mainClass=com.saved2serve.seed.ChurchDirectorySeed"` after setting `SAVED2SERVE_TABLE_NAME`, `SAVED2SERVE_KMS_KEY_ID`, and AWS credentials for the intended account. No AWS table target is configured in this checkout, so no live database write has been performed.

## Day 08 deliverable included

The devotion-link field contract, approved source rules, leader review workflow, public-data boundary, and review scenarios are documented in [docs/day-08-devotion-link-fields-source-and-approval.md](docs/day-08-devotion-link-fields-source-and-approval.md). `DevotionLinkRepository` stores only approved records with required provenance and verification metadata, and retrieves published links through the shared DynamoDB status index. `GET /v1/devotions`, implemented by `DevotionApiHandler`, returns only the public devotion ID, title, validated URL, and platform for approved links. URL validation accepts only HTTPS links on the exact YouTube hosts in the allowlist. Focused tests cover validation, repository behavior, and safe endpoint responses. Candidate review management and the admin devotion API remain unimplemented; repository results include internal review metadata and must not be returned directly from public handlers.

## Day 09 deliverable included

`HelpRequest` and `Feedback` define required, trimmed message values with a 2,000-Unicode-code-point limit. The existing `EngagementRequest` and `Engagement` apply bounded limits to name, email, area code, phone, and message fields at request and domain boundaries. Help requests, suggestions, and testimonies remain private pending-review engagements. See [docs/day-09-help-request-and-feedback-types-with-input-limits.md](docs/day-09-help-request-and-feedback-types-with-input-limits.md) and `InterestEngagementRecordsTest` for the input contract and test evidence.

`POST /v1/engagements` validates consented help requests, suggestions, and testimonies, then persists them as `PENDING_REVIEW`. The handler enforces a JSON field allowlist and 16 KiB payload limit, returns generic non-disclosing responses, and never echoes submitted content. The DynamoDB repository and mapper refuse non-pending submissions; KMS encrypts email, name, optional phone, and message before storage, with email indexed only by hash. See [docs/day-09-engagement-creation-and-persistence-flow.md](docs/day-09-engagement-creation-and-persistence-flow.md) for the request contract and validation evidence. Review and public publishing routes remain outside this flow.

## Day 10 deliverables included

[docs/day-10-cognito-jwt-authorizer.md](docs/day-10-cognito-jwt-authorizer.md) specifies the Cognito HTTP API JWT authorizer, least-privilege scopes for leader routes, the required verified `Leaders` group check in each integration Lambda, and a deployment verification matrix. The shared `LeaderAuthorization` guard checks the API Gateway JWT context for the exact route scope and leader-group claim, failing closed when either is missing; its tests cover authorized leaders, insufficient scopes, missing context, non-leaders, and malformed claims. Public-handler tests verify that interest, engagement, church directory, and devotion routes succeed without an authorization token while preserving privacy controls. The guide defines deployment configuration; no live AWS authorizer is configured by this repository.

## Day 11 deliverables included

Leader engagement review is implemented in `LeaderEngagementApiHandler`: `GET /v1/admin/engagements` supports status, inclusive lower/exclusive upper time bounds, and bounded opaque-cursor pagination; `PATCH /v1/admin/engagements/{engagementId}` allows only `PENDING_REVIEW` to `APPROVED` or `REJECTED`. Both routes require their Day 10 scope and the `Leaders` group. DynamoDB conditionally updates the engagement and writes a minimal reviewer/status/timestamp audit record atomically. Reviewed submissions remain private and are not published by this flow. See [docs/day-11-leader-engagement-review.md](docs/day-11-leader-engagement-review.md) for route contracts, privacy boundaries, and test evidence; it does not claim deployment to AWS.

## Day 12 deliverables included

Interest and engagement submissions accept a separate optional `followUpConsent` choice, defaulting to false and recorded with a server timestamp. Only opted-in records are queued to SQS; messages contain only record IDs and record types. The worker rechecks current consent before SES delivery, uses generic email content, and returns per-message retry failures. Withdrawal uses a signed token and a confirmation-page POST, so link-scanner GET requests cannot revoke consent. See [docs/day-12-opt-in-follow-up-notifications.md](docs/day-12-opt-in-follow-up-notifications.md) for the request flow, required environment variables, least-privilege AWS configuration, retry caveats, and test command. AWS resources are not provisioned or deployed by this change.

## Day 13 deliverables included

The responsive static landing page and interest form are in `web/`. The form collects only name, email, and area/postcode as required values; phone and follow-up email permission are optional. It submits to `POST /v1/interests`, displays safe success/error outcomes, and avoids persisting or logging form values. Configure the HTTPS API base URL in `web/config.js`; the site and API must be hosted with appropriate CORS. See [docs/day-13-public-landing-and-interest-form.md](docs/day-13-public-landing-and-interest-form.md) for privacy, deployment setup, accessibility notes, and test instructions. AWS hosting and API configuration are not provisioned here.

## Day 14 deliverables included

The public site includes exact area/postcode church discovery using `GET /v1/churches?areaCode=...` and published devotion discovery using `GET /v1/devotions`. Church rendering is limited to approved public directory fields, and devotion rendering accepts only approved YouTube HTTPS links; review metadata and unapproved content are excluded. Both flows provide loading, empty, and generic retryable error states with responsive layouts. See [docs/day-14-discovery-pages.md](docs/day-14-discovery-pages.md) for contracts and verification evidence. AWS hosting and CORS configuration remain deployment tasks.

## Day 15 deliverables included

The private leader portal is in `web/leader.html`. It uses Cognito authorization-code sign-in with PKCE, keeps the access token in memory only, and calls the protected engagement queue/review API with the required bearer token. Leaders can filter and paginate the queue, inspect private submissions, and request approve/reject transitions; the API remains authoritative for group/scope authorization and transition validation. See [docs/day-15-leader-portal.md](docs/day-15-leader-portal.md) for Cognito/API setup, session behavior, and test evidence. Cognito, API Gateway, and AWS hosting are not configured or deployed by this change.

## Java and dependency baseline

- Java 21
- Maven 3.9+
- AWS Lambda Java Core and Events
- AWS SDK for Java 2.x DynamoDB, KMS, SQS, and SES v2 clients
- Jackson for JSON serialization
- JUnit 5 and Mockito for testing

## Local setup and validation

Use these commands to run the test suite and create the packaged JAR:

```bash
mvn test
mvn package
```

To verify both together:

```bash
mvn test -q && echo '--- TEST_OK ---' && mvn package -q && echo '--- PACKAGE_OK ---'
```

Expected success output:

```text
--- TEST_OK ---
--- PACKAGE_OK ---
```

## CI quality gate

The repo includes a GitHub Actions workflow that runs the Maven test suite automatically on pushes and pull requests to the main branch. This keeps the project aligned with the Day 02 requirement for consistent validation in CI.

## Lambda bootstrap and testability

The Lambda bootstrap is intentionally minimal and designed to support local and test execution without requiring AWS credentials to be present in the environment. The bootstrap keeps the project focused on serverless entry points without expanding the privacy footprint of the MVP.

## Privacy, retention, threat, and release decisions

The project keeps the MVP intentionally narrow and privacy preserving:

- public records require explicit approval and are never automatically published
- only minimal fields are collected, with area-code matching instead of precise location data
- full residential addresses, exact coordinates, and sensitive metadata are rejected
- admin-only review flows remain protected by Cognito/JWT-based authorization
- retention is limited to the minimum required operational and review period
- TLS/HTTPS and least-privilege controls are required for public and service-to-service traffic
- release readiness depends on privacy review and validation against acceptance scenarios

## Acceptance scenario summary

The acceptance scenarios cover the core MVP flows and expected behavior:

- consent-based registration from QR and public landing flows
- area-code-based service matching and empty-state handling
- approved devotion links only over HTTPS
- private help, testimony, and suggestion submissions stored as pending review
- leader access through Cognito JWT authentication and protected admin routes
- leader review queue filtering by status and time, cursor pagination, and safe status transitions

## Validation rules enforced in code

The project validates and rejects non-compliant input, including:

- full residential addresses
- precise GPS coordinates
- duplicate or missing required consent flags
- invalid email or malformed public submission data
- non-HTTPS or unapproved devotion links

## Documentation structure

Additional supporting materials are available in the docs folder:

- day-02-java-21-maven-project.md
- day-02-git-quality-gates.md
- day-02-lambda-bootstrap.md
- privacy-retention-threat-release.md
- acceptance-scenarios.md
- architecture.md
- day-04-dynamodb-keys-indexes-and-access-patterns.md
- day-06-api-gateway-interest-endpoint.md
- day-07-approved-church-directory-and-service-areas.md
- day-07-seeding-church-candidates.md
- day-10-cognito-jwt-authorizer.md
- day-11-leader-engagement-review.md
- day-12-opt-in-follow-up-notifications.md
- day-13-public-landing-and-interest-form.md
- day-14-discovery-pages.md
- day-15-leader-portal.md

The architecture document is intentionally kept separate from this README.
