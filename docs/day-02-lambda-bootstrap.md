# Day 02: Lambda bootstrap and credential-free construction test

## Deliverable summary

This Day 02 task adds a minimal Lambda bootstrap abstraction for the Saved2ServeConnect Java 21 baseline and verifies that the bootstrap can be constructed without AWS credentials being present in the environment.

## Scope

- create a lightweight Lambda bootstrap wrapper based on a Java `Supplier`
- allow credential-free construction for local, test, and bootstrap scenarios
- keep the MVP aligned with privacy rules by avoiding broader AWS client configuration or sensitive runtime assumptions
- document the bootstrap operation and the verification approach

## Privacy boundary

The bootstrap intentionally does not introduce location storage, public publishing, or broader credential handling. It simply prepares a minimal function entry point while preserving the "area-code only" and privacy-first MVP constraints defined for the project.

## Review evidence

The project includes a Java test that clears the `aws.*` system properties before constructing the bootstrap and asserts that the supplied delegate still executes successfully.

```bash
mvn test
```
