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

## Java and dependency baseline

- Java 21
- Maven 3.9+
- AWS Lambda Java Core and Events
- AWS SDK for Java 2.x DynamoDB and KMS clients
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

- public records are treated as pending review and never automatically published
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
- review queue filtering and pagination for pending submissions

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

The architecture document is intentionally kept separate from this README.
