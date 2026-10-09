# Day 02: Git ignore rules and GitHub Actions quality gates

## Deliverable summary

This Day 02 task configures the repository guardrails needed to keep the Java 21 Maven project clean and consistently validated.

## Included configuration

- expanded ignore rules for build output, IDE/editor files, logs, OS artifacts, and local environment settings
- GitHub Actions workflow to run the Maven quality gate on pushes and pull requests to the main branch
- Java 21 toolchain configuration for the pipeline

## Privacy and MVP boundaries

The workflow is intentionally narrow: it validates the project build and unit tests without introducing any public data exposure or additional data collection. The quality gate does not alter the MVP privacy boundaries, which remain area-code-based and consent-sensitive.

## Review evidence

The workflow runs the same Maven validation used by local development:

```bash
mvn test
```
