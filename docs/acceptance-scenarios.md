# Acceptance scenarios

This file is a shorthand reference for the MVP acceptance scenarios. The complete scenario set and broader project context are consolidated in the README, so this document stays intentionally brief.

## Core scenarios

- consent-based public registration with area-code input
- rejection of full residential addresses and GPS coordinates
- pending-review handling for help requests, suggestions, and testimonies
- approved devotion links only over HTTPS
- leader-only access through Cognito JWT authentication
- review queue filtering and pagination for pending submissions
- approved church results for an exact active area-code match, with an empty result when no approved church is listed

## Policy rules

- no precise location data collection
- no auto-publication of submissions
- no leakage of personal data in validation errors
- admin routes require authentication and authorization

See the README for the full implementation summary and supporting project details.

## Day 07 directory review scenarios

- pending, rejected, and inactive churches are not returned by public directory lookups
- an approved church is returned only for its explicitly listed active service-area codes
- a code prefix, a nearby area, or a code with a different leading zero does not create a match
- directory responses expose only the church ID, verified public name, and optional website
- an unpopulated directory stays empty until verified church records and an operating geography are approved
- API-discovered church candidates remain pending review and are not exposed as approved directory records
- service-area mappings are included only when verified against the canonical area register; mappings are never inferred from proximity or coordinates
- blank lookup codes are rejected, surrounding whitespace is trimmed, and leading zeroes are preserved

The endpoint, lookup service, mapper, and DynamoDB repository filter are covered
by `ChurchDirectoryApiHandlerTest`, `ChurchDirectoryServiceTest`,
`DynamoDbRecordMapperTest`, and `DynamoDbSaved2ServeRepositoryTest`.
See [day-07-approved-church-directory-and-service-areas.md](day-07-approved-church-directory-and-service-areas.md) for the data contract, approval rules, and review evidence.
See [day-07-seeding-church-candidates.md](day-07-seeding-church-candidates.md) for the candidate-import workflow and readiness blockers.

  Scenario: Leader filters the review queue to only unreviewed items
    Given a leader is authenticated with a valid Cognito JWT
    And the system contains both pending and reviewed submissions
    When the leader requests the queue with a status filter for PENDING_REVIEW
    Then the system returns only submissions still awaiting review
    And reviewed submissions are not included in that view
```

### 7. Status transitions

```gherkin
Feature: Status transitions

  Scenario: Leader changes a submission from PENDING_REVIEW to approved
    Given a leader is authenticated with a valid Cognito JWT
    And a submission exists with status PENDING_REVIEW
    When the leader approves the submission
    Then the system updates the submission status to APPROVED
    And the submission remains accessible only to authorized leader workflows
    And the submission is not published to public APIs

  Scenario: Leader changes a submission from PENDING_REVIEW to rejected
    Given a leader is authenticated with a valid Cognito JWT
    And a submission exists with status PENDING_REVIEW
    When the leader rejects the submission
    Then the system updates the submission status to REJECTED
    And the rejection reason is stored internally for leader review
    And the public API does not expose rejected content

  Scenario: Leader attempts to change a submission without a valid token
    Given a caller does not have a valid Cognito JWT
    When the caller attempts to update a submission status
    Then the system rejects the update
    And the system does not change the submission status
    And the response is unauthorized
```

### 8. Internal leader notes

```gherkin
Feature: Internal leader notes

  Scenario: Leader adds an internal note to a submission
    Given a leader is authenticated with a valid Cognito JWT
    And a submission exists in the review queue
    When the leader adds an internal note to that submission
    Then the system stores the note in an internal leader-only record
    And the note is not returned by public APIs
    And the note is not included in any public-facing marketing or display data

  Scenario: Leader note creation is rejected when the caller is unauthenticated
    Given a caller is not authenticated with a valid Cognito JWT
    When the caller attempts to add an internal note
    Then the system rejects the request
    And no note is stored
    And the response is unauthorized
```

### 9. Opt-in follow-up queueing

```gherkin
Feature: Opt-in follow-up queueing

  Scenario: Leader queues a follow-up only for a consented contact
    Given a leader is authenticated with a valid Cognito JWT
    And a public submission exists
    And the contact record includes explicit opt-in consent for follow-up
    When the leader queues the contact for follow-up
    Then the system adds the record to the opt-in follow-up queue
    And the contact is not contacted unless they opted in

  Scenario: Leader cannot queue follow-up for a non-consented contact
    Given a leader is authenticated with a valid Cognito JWT
    And a public submission exists
    And the contact record does not include explicit opt-in consent
    When the leader attempts to queue a follow-up
    Then the system rejects the operation
    And the contact is not added to the follow-up queue

  Scenario: Follow-up queueing is blocked for unauthenticated callers
    Given a caller does not have a valid Cognito JWT
    When the caller attempts to queue a follow-up
    Then the system rejects the request
    And no follow-up item is created
    And the response is unauthorized
```
