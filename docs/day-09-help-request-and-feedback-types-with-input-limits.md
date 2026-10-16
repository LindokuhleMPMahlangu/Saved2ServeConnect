# Day 09 - Help-request and feedback types with input limits

## Scope and privacy boundary

Help requests and feedback are private engagement content. This deliverable
defines bounded domain values and validates the existing engagement request
and persistence records. It does not add an API route, notification workflow,
review interface, or public display of submissions. Engagements remain pending
review and are not auto-published.

The existing `EngagementType` values remain unchanged:

- `HELP_REQUEST` represents a private request for help.
- `SUGGESTION` and `TESTIMONY` represent feedback content.

`HelpRequest` and `Feedback` hold only the submitted message. The existing
`EngagementRequest` remains the request envelope for consent and any permitted
contact or area-code fields. No full address, precise coordinates, sensitive
metadata, or additional contact details are introduced.

## Input contract

Text is trimmed before validation. Length is measured in Unicode code points,
not UTF-16 code units.

| Input | Requirement | Maximum |
| --- | --- | ---: |
| `name` | Required | 100 characters |
| `email` | Required | 254 characters |
| `areaCode` | Required | 16 characters |
| `phone` | Optional | 32 characters |
| Help-request `message` | Required | 2,000 characters |
| Feedback `message` | Required | 2,000 characters |
| Engagement `message` | Required | 2,000 characters |

Consent remains required by `EngagementRequest`; a missing or false consent
value is rejected. Limits are enforced in `EngagementRequest` before accepting
input and again in `Engagement` to protect the domain and persistence boundary.
`HelpRequest` and `Feedback` enforce the same message limit independently.

## Review evidence

`InterestEngagementRecordsTest` verifies normalization, exact-limit acceptance,
over-limit rejection, required message content, Unicode code-point counting,
and the persisted engagement limit. Run the focused tests with:

```powershell
mvn -Dtest=InterestEngagementRecordsTest test
```
