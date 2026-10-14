# Day 07 - Seeding church candidates with explicit area mappings

## Current status: blocked on approved source data

The pilot scope is Johannesburg, South Africa, using four-digit postal codes as
area codes. No church records are seeded yet: an authoritative, licensed
Johannesburg postal-code register and verified church-to-service-area mappings
have not been supplied.

## Candidate discovery and source restrictions

Google Places API results must not be exported into a static seed file or
persisted as directory content unless a separate license permits that use.
Google's [Places API policies](https://developers.google.com/maps/documentation/places/web-service/policies)
restrict pre-fetching, caching, and storing Places content, except for stated
exceptions such as place IDs, and require attribution when content is displayed.
Use Places content only in a compliant live-display workflow, or use it as a
lead to independently verify the church's information from a source that
authorizes publication.

Other discovery sources are acceptable only when their license permits the
intended use and any attribution/share-alike obligations can be met. For every
record, retain the independently verified public source reference, retrieval
or verification date, and approval evidence. A listing on a map is a lead, not
proof that a church authorizes a public directory listing or serves a particular
postal area.

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

1. an authoritative, licensed postal-code register for Johannesburg
2. church records from a source that permits publication, independently verified
   public names/websites, and approval evidence
3. explicit evidence from each church for every postal code it serves; a church
   address, nearby location, or map result is not evidence of service coverage

Review checks:

| Check | Expected outcome |
| --- | --- |
| An authoritative postal-code register is unavailable | No production area mappings are created. |
| A source's terms do not allow persisted directory data | Its content is not copied into a static seed; use it only in an allowed workflow or choose another source. |
| A candidate is imported from a permitted source | It starts `PENDING_REVIEW` with source and retrieval-date metadata. |
| An area code is inferred from proximity or coordinates | The mapping is rejected; no inferred code is stored. |
| A candidate or mapping has not been reviewed | It is not public and cannot be marked approved. |
| A candidate passes review | Only verified explicit codes are listed, and the record may be approved. |
| A lookup has no approved exact match | It returns no church rather than broadening the search. |

This is documentation and review evidence only. No live records, external API
integration, or public seed operation is included until the source, postal-code
register, and church-specific service-area evidence above are available.
