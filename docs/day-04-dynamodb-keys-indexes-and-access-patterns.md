# Day 04: DynamoDB keys, indexes, and access patterns

Prepared for review on 2026-10-11.

## Goal

Define a DynamoDB design for the Save2Serve MVP that keeps the public surface minimal, supports admin review flows, and respects the privacy boundaries already used by the Java model and validation rules.

## Design principles

- Use a single table pattern to keep the design simple and reviewable.
- Store only minimal, consented data and never hold full residential addresses or precise coordinates.
- Use area-code-based keys rather than exact address or GPS-based identifiers.
- Hash personally identifying values such as email before using them in lookup keys.
- Keep submissions in a pending-review state until an admin approves or rejects them.

## Table: Save2ServeRecords

The table stores all public and admin-facing records in one place.

### Primary keys

- PK: `ENTITY#<recordId>`
- SK: `TYPE#<entityType>#<createdAt>`

This gives each record a stable identity while keeping the table flexible for multiple record types such as interest, engagement, and future admin metadata.

### Record shape

Each item includes:

- `recordId` - UUID or generated identifier.
- `entityType` - `INTEREST` or `ENGAGEMENT`.
- `createdAt` - ISO-8601 or epoch timestamp.
- `name` - optional display name, not used in lookup keys.
- `emailHash` - SHA-256 of the email value, not the raw email itself.
- `areaCode` - coarse-grained location only.
- `consent` - boolean for consented registration and submissions.
- `status` - `PENDING_REVIEW`, `APPROVED`, or `REJECTED` for engagement items.
- `type` - engagement type for help requests, suggestions, or testimony, if applicable.
- `message` - pending review content.

This keeps the schema consistent with the privacy-constrained domain records already defined in the Java code.

## Global secondary indexes

### GSI1: Review queue

- Name: `StatusCreatedAtIndex`
- PK: `STATUS#<status>`
- SK: `CREATED_AT#<createdAt>`

Purpose:

- Return all pending review records for admin workflows.
- Query `STATUS#PENDING_REVIEW` to support the review queue without exposing unpublished records to anonymous users.

### GSI2: Area-based matching

- Name: `AreaCreatedAtIndex`
- PK: `AREA#<areaCode>`
- SK: `CREATED_AT#<createdAt>`

Purpose:

- Return all approved records for a specific area.
- Keep area matching coarse-grained and privacy-preserving.

### GSI3: Duplicate and user lookup

- Name: `EmailHashIndex`
- PK: `EMAIL_HASH#<sha256(email)>`
- SK: `ENTITY#<recordId>`

Purpose:

- Prevent duplicate interest or engagement submissions from the same person.
- Allow a safe, privacy-conscious identity lookup without storing raw email addresses in the key material.

## Access patterns

### 1. Create a new interest

Operation:

- Write an `INTEREST` item with `PK = ENTITY#<recordId>` and `SK = TYPE#INTEREST#<createdAt>`.
- Store `emailHash`, `areaCode`, and `consent`.
- Use `EmailHashIndex` to detect duplicates.

Why it matches the MVP:

- Consent is required.
- Only area code is retained.
- No full address or location coordinates are stored.

### 2. Create a new engagement submission

Operation:

- Write an `ENGAGEMENT` item with `status = PENDING_REVIEW`.
- Include `type`, `message`, `areaCode`, and `emailHash`.
- Place the item into `StatusCreatedAtIndex` under `STATUS#PENDING_REVIEW`.

Why it matches the MVP:

- Public submissions remain unpublished until reviewed.
- The admin queue is separate from the public API surface.

### 3. Admin list pending review queue

Operation:

- Query `StatusCreatedAtIndex` with `PK = STATUS#PENDING_REVIEW`.
- Sort by `SK` descending or ascending based on recent review order.

Why it matches the MVP:

- Admin-only review flows remain separated from anonymous traffic.
- No unpublished records are returned from public APIs.

### 4. Area-based matching

Operation:

- Query `AreaCreatedAtIndex` with `PK = AREA#<areaCode>`.
- Filter by approved records only.

Why it matches the MVP:

- Matching stays at the area-code level.
- No street-level or precise-location data is required.

### 5. Duplicate guard for consented submissions

Operation:

- Query `EmailHashIndex` for the hashed email.
- If the same hash exists for a recent interest or engagement, reject or de-duplicate according to business policy.

Why it matches the MVP:

- It prevents repeated consented submissions without storing raw email in key material.

## Privacy safeguards

- Use a SHA-256 hash for emails; do not store raw email as an index key.
- Use coarse-grained `areaCode` values only.
- Reject full-address or GPS data before persistence.
- Keep all public submissions in `PENDING_REVIEW` until an admin approves them.
- Restrict review routes to authenticated admin access with Cognito/JWT checks.

## Repository contracts

The application-facing persistence boundary is defined in `com.saved2serve.repository` and depends only on the domain records and standard Java collection types. `InterestRepository` supports saving an interest, area-code lookup, and duplicate checks using an email hash. `EngagementRepository` supports saving submissions and querying by review status, optionally narrowed to an area code.

These interfaces do not expose DynamoDB tables, keys, SDK request/response objects, or AWS client types. `EngagementRepository` also provides a `Page<Engagement>` query accepting a page size and opaque cursor, so queue clients can fetch bounded pages without depending on AWS SDK types.

## DynamoDB mapping and pagination

`DynamoDbSaved2ServeRepository` implements both contracts using injected AWS SDK v2 DynamoDB and KMS clients. Table name and KMS key ID are constructor configuration, so credentials and clients continue to follow the AWS SDK credential/provider chain chosen by the application.

Each write generates a UUID record ID and a fixed-width, nanosecond-precision UTC creation timestamp so lexical index ordering is chronological. Items use `PK = ENTITY#<recordId>` and `SK = TYPE#<entityType>#<createdAt>`. The index attributes are:

| Index | Partition attribute | Sort attribute | Projection |
|---|---|---|---|
| `StatusCreatedAtIndex` | `GSI1PK = STATUS#<status>` | `GSI1SK = CREATED_AT#<createdAt>#<recordId>` | ALL |
| `AreaCreatedAtIndex` | `GSI2PK = AREA#<areaCode>` | `GSI2SK = CREATED_AT#<createdAt>#<recordId>` | ALL |
| `EmailHashIndex` | `GSI3PK = EMAIL_HASH#<sha256>` | `GSI3SK = ENTITY#<recordId>` | KEYS_ONLY |

The ID suffix makes timestamps with equal values deterministic in index order. Email is encrypted/decrypted with the configured symmetric KMS key and an encryption context containing the record ID and entity type; values over KMS's 4-KiB plaintext limit are rejected. Only the ciphertext is persisted; trimmed, lowercase email is SHA-256 hashed for the duplicate lookup index. Raw email is not used in DynamoDB keys or stored as a plaintext attribute. Since area and status indexes return record fields, they require `ALL` projection; the duplicate check needs only index keys and uses `KEYS_ONLY`.

The paginated status query uses `StatusCreatedAtIndex`, descending sort order, and a page size from 1 to 100. Its cursor encodes the DynamoDB `LastEvaluatedKey` as URL-safe Base64 JSON and must be treated as opaque by callers; pass the returned cursor unchanged to fetch the next page. An absent cursor means the first page, and a null `nextCursor` means there are no more results. The legacy list-returning status method drains all pages and should not be used for large review queues.

Repository queries do not perform authorization. Queue and decrypted contact data must only be exposed through the authenticated admin/JWT-protected application routes. AWS IAM permissions should scope DynamoDB table/index access and KMS use to the service role and configured key.

## Review evidence

`DynamoDbRecordMapperTest` verifies KMS-backed email mapping, absence of plaintext email fields, and status/area/hash index attributes. `DynamoDbSaved2ServeRepositoryTest` verifies the area, status, and email-hash query requests; mapped and empty results; continuation-key handling; page-size validation; and propagation of DynamoDB and KMS failures (including preventing a write when email encryption fails). AWS clients are mocked, so tests require no live AWS account, credentials, or data.
