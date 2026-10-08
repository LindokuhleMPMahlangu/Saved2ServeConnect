# Acceptance scenarios

This document defines TDD-ready acceptance scenarios for the Saved2ServeConnect MVP. It covers public visitor flows and leader-only workflows, while enforcing strict privacy boundaries.

## Privacy and validation rules

The following rules apply to every scenario in this document:

- Do not collect or require a full residential address.
- Do not collect or require GPS coordinates or location precision beyond a service area or area code.
- Public submissions must be stored as `PENDING_REVIEW` and must never be automatically published.
- Any validation error must avoid echoing personal data, exact coordinates, or sensitive submission text in the response.
- Leader routes must be protected by Cognito authentication and JWT authorization.

## Public workflows

### 1. QR landing and consent-based registration

```gherkin
Feature: QR landing and consent-based registration

  Scenario: Visitor registers successfully from a QR landing page with valid consent
    Given a visitor opens a church QR landing page
    And the visitor provides a valid name
    And the visitor provides a valid email address
    And the visitor provides an area code for service matching
    And the visitor checks the consent checkbox for voluntary registration and follow-up
    When the visitor submits the registration form
    Then the system creates a new public interest record
    And the record is stored with status PENDING_REVIEW
    And the system confirms the registration was received
    And the system does not require a full residential address
    And the system does not require precise GPS coordinates

  Scenario: Visitor registration is rejected when consent is unchecked
    Given a visitor opens a church QR landing page
    And the visitor provides a valid name
    And the visitor provides a valid email address
    And the visitor provides an area code for service matching
    And the consent checkbox remains unchecked
    When the visitor submits the registration form
    Then the system rejects the registration
    And no record is created
    And the response indicates that consent is required
    And the response does not reveal any personal data such as the email address

  Scenario: Visitor registration is rejected when the email format is invalid
    Given a visitor opens a church QR landing page
    And the visitor provides a valid name
    And the visitor provides an invalid email address value
    And the visitor provides an area code for service matching
    And the visitor checks the consent checkbox for voluntary registration and follow-up
    When the visitor submits the registration form
    Then the system rejects the registration
    And no record is created
    And the response indicates the email is invalid
    And the response does not echo the submitted email value

  Scenario: Visitor registration is rejected when a full residential address is supplied
    Given a visitor opens a church QR landing page
    And the visitor provides a valid name
    And the visitor provides a valid email address
    And the visitor provides an area code for service matching
    And the visitor includes a street address or full residential address
    And the visitor checks the consent checkbox for voluntary registration and follow-up
    When the visitor submits the registration form
    Then the system rejects the submission
    And no record is created
    And the response indicates that full residential addresses are not accepted
    And the response does not include the submitted address details

  Scenario: Visitor registration is rejected when GPS coordinates are supplied
    Given a visitor opens a church QR landing page
    And the visitor provides a valid name
    And the visitor provides a valid email address
    And the visitor provides an area code for service matching
    And the visitor includes GPS latitude and longitude values
    And the visitor checks the consent checkbox for voluntary registration and follow-up
    When the visitor submits the registration form
    Then the system rejects the submission
    And no record is created
    And the response indicates that precise location coordinates are not accepted
    And the response does not expose the submitted coordinates
```

### 2. Church service-area matching

```gherkin
Feature: Church service-area matching

  Scenario: Approved church list is returned for an exact service-area match
    Given a visitor has entered an area code
    And the system has approved churches for that exact area code
    When the visitor requests church suggestions
    Then the system returns only approved churches for that exact area code
    And the response does not include unapproved churches
    And the response does not require a full residential address
    And the response does not require GPS coordinates

  Scenario: Honest empty state is returned when no approved church matches the area code
    Given a visitor has entered an area code
    And there are no approved churches for that exact area code
    When the visitor requests church suggestions
    Then the system returns an empty-state message
    And the message states that no approved churches were found for the area code
    And the message does not imply that an unapproved church is valid

  Scenario: Church suggestion request is rejected when precise address data is sent
    Given a visitor provides an exact street address or geolocation payload
    And the visitor also provides an area code
    When the visitor requests church suggestions
    Then the system rejects the request
    And the response indicates that precise location details are not accepted
    And the response does not echo the submitted address or coordinates
```

### 3. Devotion links discovery

```gherkin
Feature: Devotion links discovery

  Scenario: Approved devotion links are returned for supported HTTPS platforms
    Given the system has a weekly devotion list
    And each devotion link uses HTTPS
    And each devotion link belongs to an approved platform
    When a visitor requests the devotion list
    Then the system returns only the approved devotion links
    And the response excludes unapproved sources

  Scenario: Non-HTTPS devotion links are filtered out
    Given the system has a weekly devotion list
    And one devotion link uses HTTP instead of HTTPS
    And the platform is otherwise approved
    When a visitor requests the devotion list
    Then the system does not include that link in the response

  Scenario: Unapproved platform links are filtered out
    Given the system has a weekly devotion list
    And one devotion link uses HTTPS
    And the destination platform is not in the approved platform list
    When a visitor requests the devotion list
    Then the system does not include that link in the response

  Scenario: Mixed-quality devotion data is sanitized before display
    Given the system has multiple devotion links
    And some links are approved and HTTPS
    And other links are HTTP or use unapproved platforms
    When a visitor requests the devotion list
    Then the system displays only the valid approved links
    And the system excludes invalid entries silently without exposing internal validation errors
```

### 4. Private engagement submissions

```gherkin
Feature: Private engagement submissions

  Scenario: Help request is submitted and stored as pending review
    Given a visitor submits a help request for a leader
    And the visitor provides a valid name and contact method
    And no full address or GPS coordinates are included
    When the help request is accepted
    Then the system stores the submission with status PENDING_REVIEW
    And the system does not publish the request publicly
    And the system does not return the submission from public APIs

  Scenario: Testimony is submitted and stored as pending review
    Given a visitor submits a testimony for review
    And the testimony includes no street address or GPS coordinates
    When the testimony is accepted
    Then the system stores the submission with status PENDING_REVIEW
    And the system does not auto-publish the testimony
    And the system does not return the testimony from public APIs

  Scenario: Suggestion is submitted and stored as pending review
    Given a visitor submits a suggestion for a leader
    And the suggestion includes no full residential data
    When the suggestion is accepted
    Then the system stores the submission with status PENDING_REVIEW
    And the system does not publish the suggestion publicly
    And the system does not return the suggestion from public APIs

  Scenario: Validation error does not leak personal data
    Given a visitor submits an engagement record with invalid or missing required data
    When the system rejects the submission
    Then the response returns a generic validation error
    And the response does not include the submitted email, phone number, exact text, or sensitive metadata
    And the system does not reveal personal identifiers in error messages
```

## Leader workflows

### 5. Cognito authentication and JWT authorization

```gherkin
Feature: Leader authentication and authorization

  Scenario: Leader with valid Cognito JWT can access protected admin routes
    Given a leader has a valid Cognito access token
    And the token has not expired
    And the token is accepted by the Cognito authorizer
    When the leader requests a protected admin route
    Then the system authorizes the request
    And the system returns the requested leader-only data

  Scenario: Leader without a valid JWT is denied access
    Given a caller has no valid Cognito JWT
    When the caller requests a protected admin route
    Then the system rejects the request
    And the response is unauthorized
    And no protected engagement data is returned

  Scenario: Leader with an expired or invalid JWT is denied access
    Given a leader has an expired or malformed JWT
    When the leader requests a protected admin route
    Then the system rejects the request
    And the response is unauthorized
    And the system logs the failure without exposing token values
```

### 6. Review queue pagination and filtering

```gherkin
Feature: Review queue pagination and filtering

  Scenario: Leader reviews a paginated queue of pending submissions
    Given a leader is authenticated with a valid Cognito JWT
    And the system has more than one pending submission
    When the leader requests the review queue
    Then the system returns only pending submissions
    And the response includes pagination metadata
    And the response supports filtering by submission type and status

  Scenario: Leader filters the review queue to help requests only
    Given a leader is authenticated with a valid Cognito JWT
    And the system contains help requests, testimonies, and suggestions
    When the leader requests the review queue with a filter for help requests
    Then the system returns only help request submissions
    And the system excludes testimonies and suggestions from that response

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
