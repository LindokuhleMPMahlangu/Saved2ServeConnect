# Saved2ServeConnect

This repository contains the Java 21 Maven baseline for the Saved2ServeConnect MVP.

## Purpose

The project establishes a compact Java foundation for API and Lambda-based services while keeping the MVP aligned with Save2Serve privacy expectations:

- no full residential addresses
- no precise GPS coordinates
- area-code based matching only
- public records remain pending review and never auto-publish
- admin-only routes remain protected by authenticated JWT flows

## Java and dependency baseline

- Java 21
- Maven 3.9+
- AWS Lambda Java Core and Events
- Jackson for JSON serialization
- JUnit 5 and Mockito for verification

## Local validation

```bash
mvn test
```
