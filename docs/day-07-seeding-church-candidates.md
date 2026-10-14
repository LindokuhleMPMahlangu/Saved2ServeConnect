# Day 07 - Seeding church candidates with explicit area mappings

## Current status: one approved pilot mapping

The pilot scope is Johannesburg, South Africa, using four-digit postal codes as
area codes. One project-owner-approved pilot record is available:
House of Treasures Ministries, mapped to postcode `1800` based on the owner's
report that the church confirmed it serves that area. This is an owner
attestation, not an independent verification of the website or postal-code
register. No other coverage is inferred from the church's address or its broad
claim to serve Johannesburg and surrounding locations.

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

One permitted alternative for discovering candidate places is
[OpenStreetMap](https://www.openstreetmap.org/copyright), whose data is
available under the Open Database License (ODbL). Provide the required
attribution, identify the license, and review ODbL share-alike obligations
before distributing derived data. OSM tags still do not establish a church's
approved status or its service-area postcodes; verify those independently.

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
| `publicAddress` | Optional church location; include publicly only with explicit owner approval. |
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

## Approved pilot seed

`com.saved2serve.seed.ChurchDirectorySeed` contains the owner-approved
House of Treasures Ministries record for area code `1800`, including the
owner-provided public website and the address the owner requested be displayed.
The verification source reference records the project owner's attestation that
the church confirmed postcode `1800`; it does not claim independent validation.
Only `1800` is included until further postcodes are explicitly confirmed.

To write the record to DynamoDB, configure AWS credentials for the intended
account and set `SAVED2SERVE_TABLE_NAME` and `SAVED2SERVE_KMS_KEY_ID`, then run:

```powershell
mvn -DskipTests package exec:java "-Dexec.mainClass=com.saved2serve.seed.ChurchDirectorySeed"
```

The command writes an approved directory item through
`DynamoDbSaved2ServeRepository.saveApproved`. It is an upsert for the stable
church-and-area key, so review the source values and target AWS account before
running it. This checkout has no configured AWS table or credentials; the seed
is implemented and testable locally, but has not been written to a live table.
