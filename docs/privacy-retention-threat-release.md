# Privacy, retention, threat, and release decisions

This document captures the key product decisions that keep the MVP aligned with Save2Serve privacy expectations. The detailed rationale and full project context are summarized in the README.

## Decision summary

- area-code-based matching only
- public submissions remain pending review
- admin-only workflows require JWT-protected access
- minimal data collection and retention only
- HTTPS and least-privilege security requirements
- release readiness depends on privacy review and acceptance validation

## Notes

This file summarizes key product decisions; the README contains the consolidated project overview and supporting details.

## Engagement submission safeguards

- Every newly submitted engagement is stored as `PENDING_REVIEW`; neither the
  request nor the persistence mapper can set an approved or rejected status.
- The submission handler returns only a generic accepted response, does not
  echo content, and logs only fixed operational messages without submitted
  fields or exception details.
- Email is KMS-encrypted with a record-specific encryption context; only its
  SHA-256 hash is used for lookup. Name, optional phone, and free-text message
  are encoded together and KMS-encrypted before persistence, not stored in
  plaintext attributes.
- Existing legacy records containing plaintext engagement content remain
  readable by the mapper. New writes use encrypted content; any migration or
  deletion of legacy records requires a separately reviewed operational plan.
- Engagement records are not returned by a public endpoint. Any future
  moderation or review route must require authenticated and authorized leader
  access and must not make pending submissions public.
- No automatic TTL is set: choose and approve a retention period before
  implementing automated deletion.
