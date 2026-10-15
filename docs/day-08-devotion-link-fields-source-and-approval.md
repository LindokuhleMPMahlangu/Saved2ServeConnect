# Day 08 - Devotion-link fields, source, and approval workflow

## Purpose and MVP boundary

This document defines the MVP contract for curated devotion links. It does not
add a submission endpoint, public API, database table, or automated publishing
workflow. The existing `Saved2ServeValidator.validateDevotionLink` check is the
URL validation baseline; links must also pass the review process below before
they may be presented publicly.

The MVP accepts links to publicly available YouTube content only, using HTTPS
and the exact approved hosts already enforced by the validator:
`youtube.com`, `www.youtube.com`, `youtu.be`, and `www.youtu.be`. A successful
URL validation does not imply content approval.

## Record contract

| Field | Requirement | Visibility and handling |
| --- | --- | --- |
| `devotionId` | Required | Stable opaque ID; do not derive it from a person's details. |
| `title` | Required | Short, reviewed public title. Do not include personal contact details or sensitive information. |
| `url` | Required | Canonical HTTPS URL to the public devotion item on an approved YouTube host. |
| `platform` | Required | Derived from the validated URL host (`YOUTUBE`); never trust a client-supplied platform value. |
| `status` | Required | `PENDING_REVIEW`, `APPROVED`, `REJECTED`, or `INACTIVE`. |
| `sourceReference` | Required before approval | Internal reference to the original item and the evidence used to establish its provenance and permission to share. Do not expose publicly. |
| `submittedAt` | Required | Timestamp the candidate entered the review process. |
| `verifiedAt` | Required for approval | Timestamp a reviewer verified the link, source, and content. |
| `reviewedBy` | Required for approval | Internal authorized reviewer identifier; never expose publicly. |
| `reviewNote` | Optional | Internal, non-sensitive explanation for rejection or follow-up; never expose publicly. |

The YouTube item itself remains hosted by its publisher. Save2Serve stores and
displays a link and its minimal reviewed metadata; it does not download, copy,
rehost, or alter third-party media.

## Source and provenance rules

Use the public YouTube item as the content source. Prefer content published by
the church or creator represented by the link. Before approval, record an
internal `sourceReference` that lets an authorized reviewer identify the
original item and the basis for sharing it, such as the canonical item URL plus
confirmation that it is an official public channel or that permission to share
was obtained.

Do not treat a valid YouTube URL, search result, or third-party playlist as
proof of permission or suitability. Do not copy content from another platform
or persist content from a source whose terms do not permit the intended use.
If provenance or sharing permission cannot be verified, keep the record
pending or reject it. Keep source and permission evidence internal and retain
only what is needed for review and ongoing verification.

## Approval workflow

1. A candidate is created by an authorized curator and starts as
   `PENDING_REVIEW`; there is no public user submission or automatic publishing.
2. An authenticated, authorized leader checks that the title is appropriate,
   the item is publicly available, its URL uses HTTPS on an exact approved
   host, and its source and permission to share are adequately documented.
3. If all checks pass, the reviewer records `sourceReference`, `verifiedAt`,
   and `reviewedBy`, then changes the status to `APPROVED`.
4. If checks fail, the reviewer keeps it pending for clarification or marks it
   `REJECTED`, with an optional internal `reviewNote`. Rejected and pending
   links are never public.
5. If an approved link becomes unavailable, changes destination, loses
   permission, or is reported as inappropriate, an authorized reviewer marks
   it `INACTIVE` while it is rechecked. A changed URL or material content
   change must be reviewed again before it is public.

Only `APPROVED` records may be returned by a future public devotion endpoint.
That response may include only `devotionId`, `title`, `url`, and `platform`.
It must omit `sourceReference`, `reviewedBy`, `reviewNote`, and other internal
review metadata. Admin review and status changes must require authenticated,
authorized access. No personal viewer profile, viewing history, precise
location, or user-submitted testimony is part of this feature.

## Review evidence and acceptance scenarios

| Check | Expected outcome | Evidence/status |
| --- | --- | --- |
| An HTTPS link uses an approved YouTube host | URL validation accepts it; this alone does not approve publication. | Covered by `Saved2ServeValidatorTest.approvedHttpsDevotionLinksAreAccepted`. |
| A link uses HTTP or a host outside the allowlist | URL validation rejects it. | Covered by `Saved2ServeValidatorTest.httpDevotionLinksAreRejected` and `Saved2ServeValidatorTest.unapprovedDevotionPlatformsAreRejected`. |
| A candidate has not completed authorized review | It remains `PENDING_REVIEW` and is not public. | Contractual review scenario; persistence/API workflow is not implemented in this checkout. |
| Source or permission cannot be verified | The candidate is not approved or published. | Contractual review scenario. |
| An authorized leader approves a verified candidate | Required provenance and review metadata are recorded internally; only the approved public fields may be returned. | Contractual review scenario. |
| A reviewer rejects or inactivates a link | It is excluded from public results and review metadata remains private. | Contractual review scenario. |
| An approved link changes destination or loses permission | It is removed from public results pending re-review. | Contractual review scenario. |

The implemented URL-validation evidence can be run with:

```powershell
mvn -Dtest=Saved2ServeValidatorTest test
```

The record lifecycle, storage, admin review endpoint, and public `GET
/v1/devotions` behavior remain future implementation work; this document
defines their acceptance contract without implying those runtime capabilities
already exist.
