# Saved2ServeConnect

This repository contains the Day 02 baseline for the Saved2ServeConnect MVP: a Java 21 Maven project, Lambda bootstrap scaffolding, quality gates, and documented privacy-first validation.

## Purpose

The project establishes a compact Java foundation for API and Lambda-based services while keeping the MVP aligned with Save2Serve privacy expectations:

- no full residential addresses
- no precise GPS coordinates
- area-code based matching only
- public records remain pending review and never auto-publish
- admin-only routes remain protected by authenticated JWT flows
- consent is required before registration or content submissions are accepted
- only approved devotion links are accepted over HTTPS

## Day 02 scope

This repository now includes the core Day 02 deliverables:

- Java 21 Maven project and pinned dependencies
- Lambda bootstrap abstraction with credential-free construction for local/test scenarios
- Git ignore rules for build and editor artifacts
- GitHub Actions quality gate for Maven test validation on main branch pushes and pull requests
- documented prerequisites and validation steps for local development

## Java and dependency baseline

- Java 21
- Maven 3.9+
- AWS Lambda Java Core and Events
- Jackson for JSON serialization
- JUnit 5 and Mockito for verification

## Local validation and packaging

Use the Maven commands below to run the project tests and build the distributable JAR:

```bash
mvn test
mvn package
```

## CI quality gate

The project includes a GitHub Actions workflow that runs the Java 21 Maven test suite automatically on pushes and pull requests to the main branch.

## Privacy and MVP boundaries

The Day 02 deliverable intentionally stays narrow and privacy-preserving:

- no location storage beyond area-code level matching
- no public auto-publishing of pending records
- no broader AWS credential configuration in the bootstrap path
- validation remains focused on the MVP rules and consent requirements

## Verification evidence

The project was validated with the following command:

```bash
mvn test -q && echo '--- TEST_OK ---' && mvn package -q && echo '--- PACKAGE_OK ---'
```

This produced the expected success output:

```text
--- TEST_OK ---
--- PACKAGE_OK ---
```
