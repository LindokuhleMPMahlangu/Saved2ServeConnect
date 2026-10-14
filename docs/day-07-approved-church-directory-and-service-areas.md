# Day 07 - Approved church directory and service-area data

## Purpose and current data status

This document defines the MVP data contract for the public church directory and
its service areas. The repository does not contain a verified church list or an
agreed operating geography, so it intentionally adds no live church records.
Only records verified and approved by the project owner should be loaded. The
candidate import process and current readiness blockers are documented in
[Day 07 - Seeding church candidates with explicit area mappings](day-07-seeding-church-candidates.md).

## Directory data

Each church record should contain:

| Field | Requirement | Notes |
| --- | --- | --- |
| `churchId` | Required | Stable, opaque identifier. Do not derive it from a person's details. |
| `displayName` | Required | Verified public name of the church. |
| `serviceAreaCodes` | Required | One or more codes from the approved service-area register. |
| `publicWebsite` | Optional | Verified public URL; use HTTPS. Do not add personal contact details. |
| `status` | Required | `PENDING_REVIEW`, `APPROVED`, `REJECTED`, or `INACTIVE`. |
| `verifiedAt` | Required for approval | Timestamp when the church information was checked. |
| `sourceReference` | Required for approval | Internal reference to the source used to verify the record. |

The reviewer identity and source reference are internal review metadata and must
not appear in public directory responses. Only `APPROVED` records are eligible
for public results. Pending, rejected, and inactive records must not be returned.
Changing an approved church's name, website, or service-area membership requires
re-verification before the changed data is published.

## Service-area register

Each service-area entry contains:

| Field | Requirement | Notes |
| --- | --- | --- |
| `areaCode` | Required | Canonical code from the chosen, verified geography. Keep it as a string, including leading zeroes. |
| `displayLabel` | Optional | Verified, broad area label for presentation; do not use a street address. |
| `status` | Required | `ACTIVE` or `INACTIVE`. |
| `sourceReference` | Required | Internal reference for how the code and label were verified. |

Churches refer to one or more entries by `areaCode`. A code may be associated
with multiple approved churches, and a church may serve multiple codes. Do not
infer coverage for unlisted codes.

## Matching and public response rules

- Match a submitted area code only to the same canonical code in the active
  service-area register. Trim surrounding whitespace; otherwise preserve the
  code as a string. Do not strip leading zeroes, parse it as a number, match
  prefixes, or infer a nearby area.
- Return only approved churches linked to that exact active code. If there are
  no matches, return an empty result rather than widening the search.
- Return only the church's opaque public `churchId`, verified public display
  name, and optional public website. Do not expose review status, source
  references, reviewer identity, or unrelated service-area memberships.
- Keep interest matching at area-code level. Do not collect or derive a
  visitor's street address, GPS coordinates, or precise location.

## Review and data stewardship

Before a record becomes public, confirm the church's public name, the source
authorizing its listing, its active service-area codes, and any public website.
Store the verification timestamp and source reference with the record. Review
approved entries when the source changes or when a correction is reported;
mark removed or no-longer-verified entries `INACTIVE` instead of silently
leaving them in public results.

The directory is not a user-submission or automatic-publishing feature.
Church additions and changes require a separate authorized review workflow.
Until that workflow and verified records exist, the public directory should
remain empty rather than show placeholder or unapproved churches.

## Review evidence and acceptance scenarios

The following checks define review evidence for this deliverable:

| Check | Expected outcome |
| --- | --- |
| No verified church list or geography is available | No live or placeholder church records are added. |
| A church is pending, rejected, or inactive | It is excluded from public results. |
| A church is approved for one active code | It is returned only for an exact match on that code. |
| A lookup differs by prefix or leading zero from the registered code | It is not coerced into a different code or treated as a match. |
| A valid code has no approved churches | The lookup returns an empty result; it does not broaden the area. |
| Public directory data is returned | Only the verified public name and optional website are exposed. |
| A request contains a full address or precise coordinates | These remain outside the service-area contract and are not collected. |

Runtime lookup for `GET /v1/churches?areaCode=...` is implemented by
`ChurchDirectoryApiHandler`, `ChurchDirectoryRepository`, and
`ChurchDirectoryService`. The DynamoDB query uses the exact trimmed code and
filters to approved directory records; the service also rechecks the status and
code before projecting public fields without review metadata. A missing or
blank code is rejected, leading zeroes are preserved, and no match returns an
empty list. Multiple area mappings are stored as separate indexed directory
items with the same stable church ID, one item per verified exact code. No
directory records are populated until verified data and an operating geography
are supplied.
