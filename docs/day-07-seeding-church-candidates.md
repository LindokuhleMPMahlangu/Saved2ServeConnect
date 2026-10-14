# Day 07 - Seeding church candidates with explicit area mappings

## Current status: blocked on pilot geography

No church records are seeded by this deliverable. The project has not selected a
launch geography or its canonical postal/area-code system, so an API query now
could return an irrelevant or misleading directory. No area mappings can be
approved without that scope and an authoritative source for the codes.

## Recommended candidate source

Use OpenStreetMap as a source for discovering candidate church places, through
an appropriate OpenStreetMap data/API workflow. Treat its results as leads, not
as proof that a church is active, authorizes a public listing, or serves a
particular postal area. Check the source's current usage requirements and
provide the required attribution before importing or publishing derived data.

This should be a bounded, one-time import for an agreed pilot geography, not a
church lookup against the external API on each public request. Record the
retrieval date and source reference for each candidate so a reviewer can
reproduce and evaluate it.

## Candidate review and area mapping

Imported records must start as `PENDING_REVIEW`. They are not part of the public
directory and must not be labeled approved or exposed by public APIs.

For each candidate, a reviewer must verify:

- the church exists and the public display name is correct
- the church is appropriate for inclusion and has an adequate public listing
  basis
- any public website is correct and uses HTTPS
- every proposed service-area code is an exact code in the approved, active
  service-area register

Use an explicit, verified postal/area-code value from a trusted source to
propose a mapping, then check it against the canonical area register. Do not
derive a code from a church's coordinates, nearest postcode centroid, address
proximity, or a neighboring code. If a candidate has no verified explicit
mapping, leave its `serviceAreaCodes` empty and do not approve it for area-based
results.

After review, change the status to `APPROVED` only when the record and every
listed mapping have been verified; otherwise keep it pending or mark it
`REJECTED`. Store the verification timestamp and internal source reference.
Public responses must continue to include only approved churches for an exact
active area-code match.

## Import record shape

The reviewed record should conform to the directory contract in
[day-07-approved-church-directory-and-service-areas.md](day-07-approved-church-directory-and-service-areas.md):

| Field | Candidate handling |
| --- | --- |
| `churchId` | Stable opaque identifier; do not derive it from a person's details. |
| `displayName` | Candidate value; verify before approval. |
| `serviceAreaCodes` | Only explicitly verified canonical codes; otherwise empty and not approvable for matching. |
| `publicWebsite` | Optional candidate value; verify and require HTTPS before approval. |
| `status` | Always `PENDING_REVIEW` on import. |
| `verifiedAt` | Blank until a reviewer approves the record. |
| `sourceReference` | Internal source identifier and retrieval-date reference; do not expose publicly. |

Do not import church contact-person details, visitor details, residential
addresses, or visitor location data. Public place data does not change the
MVP's prohibition on collecting precise locations from people seeking help.

## Readiness and review evidence

The import must not run until the project has selected:

1. a pilot country and city/region or a bounded list of area codes
2. the canonical area-code format and an authoritative register for validation
3. the reviewer/approval process for church listings and service-area mappings

Review checks:

| Check | Expected outcome |
| --- | --- |
| Pilot geography or code register is undecided | No API query or seed file is created. |
| A candidate is imported from an external source | It starts `PENDING_REVIEW` with source and retrieval-date metadata. |
| An area code is inferred from proximity or coordinates | The mapping is rejected; no inferred code is stored. |
| A candidate or mapping has not been reviewed | It is not public and cannot be marked approved. |
| A candidate passes review | Only verified explicit codes are listed, and the record may be approved. |
| A lookup has no approved exact match | It returns no church rather than broadening the search. |

This is documentation and review evidence only. No live records, external API
integration, or public seed operation is included until the readiness decisions
above are made.
