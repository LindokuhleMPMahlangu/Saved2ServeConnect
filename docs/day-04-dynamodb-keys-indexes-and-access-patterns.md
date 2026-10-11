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

## Review evidence

This design is intentionally aligned with the existing records and validation rules in the Java codebase, including the minimal field set and the requirement that submissions remain pending review until explicitly approved.
