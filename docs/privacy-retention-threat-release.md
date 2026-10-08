# Privacy, retention, threat, and release decisions

## Purpose

This record captures the Day 01 decision set for Saved2ServeConnect. It complements the architecture and acceptance scenario documentation and documents the product boundaries that keep the MVP aligned with Save2Serve privacy expectations.

## Decision summary

- The MVP will use area-code-based matching only.
- Public submissions remain private until reviewed.
- Admin-only workflows require Cognito JWT validation.

## Privacy and data minimization decisions

- Only the minimum required fields will be collected: name, email, optional phone, area code, and consent flags.
- Full residential addresses, exact coordinates, and precise location data are explicitly out of scope.
- Public-facing APIs will never expose unreviewed or admin-only submissions.
- Validation errors must not echo personal data, exact text, or sensitive metadata.

## Retention decisions

- Data retention will be limited to the minimum time needed for review, follow-up, and operational support.
- No long-term retention is planned for raw sensitive submissions beyond the business need.
- Review and follow-up records must remain subject to consent and admin access controls.
