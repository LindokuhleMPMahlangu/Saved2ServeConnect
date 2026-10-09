# Day 02: Java 21 Maven project and pinned dependencies

## Deliverable summary

This Day 02 deliverable establishes the Java 21 Maven project baseline for Saved2ServeConnect. The project intentionally pins the required dependencies in the Maven build and keeps the implementation aligned with the privacy-first MVP boundaries described in the architecture and acceptance scenario documents.

## Included project baseline

- Java 21 compiler target
- Maven project packaging and pinned dependency versions
- AWS Lambda support for Java serverless handlers
- Jackson for JSON handling
- test stack for JUnit 5 and Mockito
- privacy validation model for registration and devotion link checks

## MVP privacy boundaries enforced in code

- full residential addresses are rejected
- precise GPS coordinates are rejected
- consent is required before registration submission is accepted
- area code is required for location matching
- only HTTPS, approved devotion platforms are accepted
- public records are treated as pending review, never auto-published

## Review evidence

The project includes automated tests that verify the validation rules and MVP privacy constraints. The default validation command is:

```bash
mvn test
```
