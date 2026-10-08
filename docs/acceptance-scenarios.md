# Acceptance Scenarios

These scenarios define the MVP behavior for Saved2ServeConnect. Location is
limited to an area code: the experience must not collect or require a full
residential address or exact coordinates.

## QR arrival and voluntary registration

```gherkin
Feature: QR arrival and voluntary registration

  Scenario: A visitor voluntarily registers with valid details and consent
    Given a visitor has arrived through a church QR code
    And the visitor provides a valid email address
    And the visitor provides their name and an area code
    And the visitor selects the required consent option
    When the visitor submits the registration form
    Then the system accepts the registration
    And confirms that the registration was received
    And does not require a full residential address or exact coordinates

  Scenario: Registration is rejected when required consent is unchecked
    Given a visitor has arrived through a church QR code
    And the visitor provides otherwise valid registration details
    And the required consent option is unchecked
    When the visitor submits the registration form
    Then the system does not create a registration
    And identifies consent as required

  Scenario: Registration is rejected when the email address is invalid
    Given a visitor has arrived through a church QR code
    And the visitor enters an invalid email address
    And the visitor selects the required consent option
    When the visitor submits the registration form
    Then the system does not create a registration
    And identifies the email address as invalid
```

## Approved-church suggestions

```gherkin
Feature: Approved-church suggestions

  Scenario: Show approved churches with an exact area-code match
    Given the visitor has provided an area code
    And approved churches exist for that exact area code
    When the visitor views church suggestions
    Then the system shows only approved churches matching that exact area code
    And does not require or use a street address or exact coordinates

  Scenario: Show an honest empty state when no church matches
    Given the visitor has provided an area code
    And no approved churches exist for that exact area code
    When the visitor views church suggestions
    Then the system shows an honest message that no approved churches were found
    And does not imply that an unapproved church is approved
```

## Weekly devotion links

```gherkin
Feature: Weekly devotion links

  Scenario: Show devotion links from approved HTTPS platforms
    Given a weekly devotion link uses HTTPS
    And its platform is approved
    When the devotion list is published for visitors
    Then the link is available in the devotion list

  Scenario Outline: Filter devotion links that fail platform or transport checks
    Given a weekly devotion link uses <scheme>
    And its platform is <platform_status>
    When the devotion list is published for visitors
    Then the link is not available in the devotion list

    Examples:
      | scheme | platform_status |
      | HTTP   | approved        |
      | HTTPS  | unapproved      |
      | HTTP   | unapproved      |
```

## Leader help requests, testimonies, and suggestions

```gherkin
Feature: Leader engagement submissions

  Scenario Outline: Keep a leader submission pending review and off public APIs
    Given a visitor submits a <submission_type> for a leader
    When the system accepts the submission
    Then the submission status is PENDING_REVIEW
    And the submission is not returned by public APIs
    And the submission is not automatically published

    Examples:
      | submission_type |
      | help request    |
      | testimony       |
      | suggestion      |
```

## Leader portal access

```gherkin
Feature: Leader portal access

  Scenario: Allow an authenticated leader to access an admin route
    Given a leader has a valid Cognito JWT
    When the leader calls a protected admin route with that JWT
    Then the request is authorized

  Scenario Outline: Deny access to an admin route without a valid Cognito JWT
    Given the caller has <token_state> Cognito credentials
    When the caller requests a protected admin route
    Then the request is denied
    And no protected engagement data is returned

    Examples:
      | token_state |
      | no          |
      | invalid     |
```
