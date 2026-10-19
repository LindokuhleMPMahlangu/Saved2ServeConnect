package com.saved2serve.persistence.dynamodb;

import com.saved2serve.domain.ChurchDirectoryRecord;
import com.saved2serve.domain.ChurchDirectoryStatus;
import com.saved2serve.domain.DevotionLink;
import com.saved2serve.domain.DevotionLinkStatus;
import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.FollowUpRecordType;
import com.saved2serve.domain.Interest;
import com.saved2serve.domain.SubmissionStatus;
import com.saved2serve.repository.ChurchDirectoryRepository;
import com.saved2serve.repository.DevotionLinkRepository;
import com.saved2serve.repository.EngagementRepository;
import com.saved2serve.repository.EngagementQueueEntry;
import com.saved2serve.repository.FollowUpRecipient;
import com.saved2serve.repository.FollowUpRepository;
import com.saved2serve.repository.InterestRepository;
import com.saved2serve.repository.Page;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.kms.KmsClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;
import java.util.UUID;

public final class DynamoDbSaved2ServeRepository
        implements InterestRepository, EngagementRepository, FollowUpRepository,
        ChurchDirectoryRepository, DevotionLinkRepository {
    public static final String STATUS_CREATED_AT_INDEX = "StatusCreatedAtIndex";
    public static final String AREA_CREATED_AT_INDEX = "AreaCreatedAtIndex";
    public static final String EMAIL_HASH_INDEX = "EmailHashIndex";
    private static final int MAX_PAGE_SIZE = 100;

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final DynamoDbRecordMapper mapper;

    public DynamoDbSaved2ServeRepository(
            DynamoDbClient dynamoDbClient,
            KmsClient kmsClient,
            String tableName,
            String kmsKeyId
    ) {
        this.dynamoDbClient = Objects.requireNonNull(dynamoDbClient, "dynamoDbClient must not be null");
        this.tableName = requireNonBlank(tableName, "tableName");
        this.mapper = new DynamoDbRecordMapper(kmsClient, kmsKeyId);
    }

    @Override
    public void save(Interest interest) {
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(mapper.toItem(interest))
                .build());
    }

    @Override
    public void save(Engagement engagement) {
        Objects.requireNonNull(engagement, "engagement must not be null");
        if (engagement.status() != SubmissionStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("New engagement submissions must be pending review.");
        }
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(mapper.toItem(engagement))
                .build());
    }

    @Override
    public void saveApproved(ChurchDirectoryRecord churchDirectoryRecord) {
        Objects.requireNonNull(churchDirectoryRecord, "churchDirectoryRecord must not be null");
        if (churchDirectoryRecord.status() != ChurchDirectoryStatus.APPROVED) {
            throw new IllegalArgumentException("Only approved churches can be seeded.");
        }
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(mapper.toItem(churchDirectoryRecord))
                .build());
    }

    @Override
    public void saveApproved(DevotionLink devotionLink) {
        Objects.requireNonNull(devotionLink, "devotionLink must not be null");
        if (devotionLink.status() != DevotionLinkStatus.APPROVED) {
            throw new IllegalArgumentException("Only approved devotion links can be published.");
        }
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(mapper.toItem(devotionLink))
                .build());
    }

    @Override
    public List<Interest> findByAreaCode(String areaCode) {
        String normalizedAreaCode = requireNonBlank(areaCode, "areaCode");
        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .indexName(AREA_CREATED_AT_INDEX)
                .keyConditionExpression("#area = :area")
                .filterExpression("#entityType = :entityType")
                .expressionAttributeNames(Map.of("#area", DynamoDbRecordMapper.GSI2PK, "#entityType", "entityType"))
                .expressionAttributeValues(Map.of(
                        ":area", DynamoDbRecordMapper.string("AREA#" + normalizedAreaCode),
                        ":entityType", DynamoDbRecordMapper.string("INTEREST")
                ))
                .scanIndexForward(false)
                .build();
        return queryAll(request).stream().map(mapper::toInterest).toList();
    }

    @Override
    public boolean existsByEmailHash(String emailHash) {
        String normalizedEmailHash = requireNonBlank(emailHash, "emailHash");
        QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .indexName(EMAIL_HASH_INDEX)
                .keyConditionExpression("#emailHash = :emailHash")
                .expressionAttributeNames(Map.of("#emailHash", DynamoDbRecordMapper.GSI3PK))
                .expressionAttributeValues(Map.of(
                        ":emailHash", DynamoDbRecordMapper.string("EMAIL_HASH#" + normalizedEmailHash)
                ))
                .select(Select.COUNT)
                .limit(1)
                .build());
        return response.count() != null && response.count() > 0;
    }

    @Override
    public FollowUpRecipient findRecipient(String recordId, FollowUpRecordType recordType) {
        Map<String, AttributeValue> item = findFollowUpItem(recordId, recordType);
        if (item == null) {
            return null;
        }
        if (!recordType.name().equals(requiredAttribute(item, "entityType"))) {
            return null;
        }
        return mapper.toFollowUpRecipient(item, recordType);
    }

    @Override
    public boolean withdrawConsent(String recordId, FollowUpRecordType recordType) {
        String normalizedId = requireNonBlank(recordId, "recordId");
        Objects.requireNonNull(recordType, "recordType must not be null");
        Map<String, AttributeValue> item = findFollowUpItem(normalizedId, recordType);
        if (item == null || !recordType.name().equals(requiredAttribute(item, "entityType"))
                || !optionalBoolean(item, "followUpConsent")) {
            return false;
        }
        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            DynamoDbRecordMapper.PK,
                            requiredAttributeValue(item, DynamoDbRecordMapper.PK),
                            DynamoDbRecordMapper.SK,
                            requiredAttributeValue(item, DynamoDbRecordMapper.SK)
                    ))
                    .updateExpression("SET #consent = :withdrawn, #withdrawnAt = :withdrawnAt")
                    .conditionExpression("#consent = :consented AND #entityType = :entityType")
                    .expressionAttributeNames(Map.of(
                            "#consent", "followUpConsent",
                            "#withdrawnAt", "followUpWithdrawnAt",
                            "#entityType", "entityType"
                    ))
                    .expressionAttributeValues(Map.of(
                            ":withdrawn", DynamoDbRecordMapper.bool(false),
                            ":consented", DynamoDbRecordMapper.bool(true),
                            ":withdrawnAt", DynamoDbRecordMapper.string(Instant.now().toString()),
                            ":entityType", DynamoDbRecordMapper.string(recordType.name())
                    ))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException alreadyWithdrawn) {
            return false;
        }
    }

    private Map<String, AttributeValue> findFollowUpItem(String recordId, FollowUpRecordType recordType) {
        String normalizedId = requireNonBlank(recordId, "recordId");
        Objects.requireNonNull(recordType, "recordType must not be null");
        QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :recordPrefix)")
                .expressionAttributeNames(Map.of(
                        "#pk", DynamoDbRecordMapper.PK,
                        "#sk", DynamoDbRecordMapper.SK
                ))
                .expressionAttributeValues(Map.of(
                        ":pk", DynamoDbRecordMapper.string("ENTITY#" + normalizedId),
                        ":recordPrefix", DynamoDbRecordMapper.string("TYPE#" + recordType.name() + "#")
                ))
                .consistentRead(true)
                .limit(1)
                .build());
        return response.items().isEmpty() ? null : response.items().getFirst();
    }

    @Override
    public String findRegistrationFingerprintByIdempotencyKey(String idempotencyKeyHash) {
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(DynamoDbRecordMapper.idempotencyKey(idempotencyKeyHash))
                        .consistentRead(true)
                        .build())
                .item();
        if (item == null || item.isEmpty()) {
            return null;
        }
        AttributeValue fingerprint = item.get("requestFingerprint");
        if (fingerprint == null || fingerprint.s() == null || fingerprint.s().isBlank()) {
            throw new IllegalStateException("Idempotency record is missing its request fingerprint.");
        }
        return fingerprint.s();
    }

    @Override
    public IdempotentSaveResult saveIdempotently(
            Interest interest,
            String idempotencyKeyHash,
            String requestFingerprint
    ) {
        Objects.requireNonNull(interest, "interest must not be null");
        String normalizedIdempotencyKeyHash = requireNonBlank(idempotencyKeyHash, "idempotencyKeyHash");
        String normalizedRequestFingerprint = requireNonBlank(requestFingerprint, "requestFingerprint");
        Map<String, AttributeValue> registrationItem = mapper.toItem(interest);
        Map<String, AttributeValue> idempotencyItem = mapper.toIdempotentItem(
                interest, normalizedIdempotencyKeyHash, normalizedRequestFingerprint
        );
        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            TransactWriteItem.builder().put(Put.builder()
                                    .tableName(tableName)
                                    .item(registrationItem)
                                    .conditionExpression("attribute_not_exists(#pk)")
                                    .expressionAttributeNames(Map.of("#pk", DynamoDbRecordMapper.PK))
                                    .build()).build(),
                            TransactWriteItem.builder().put(Put.builder()
                                    .tableName(tableName)
                                    .item(idempotencyItem)
                                    .conditionExpression("attribute_not_exists(#pk)")
                                    .expressionAttributeNames(Map.of("#pk", DynamoDbRecordMapper.PK))
                                    .build()).build()
                    )
                    .build());
            return IdempotentSaveResult.CREATED;
        } catch (TransactionCanceledException duplicateKey) {
            String existingFingerprint = findRegistrationFingerprintByIdempotencyKey(
                    normalizedIdempotencyKeyHash
            );
            if (existingFingerprint == null) {
                throw duplicateKey;
            }
            return existingFingerprint.equals(normalizedRequestFingerprint)
                    ? IdempotentSaveResult.REPLAYED
                    : IdempotentSaveResult.IDEMPOTENCY_KEY_CONFLICT;
        }
    }

    @Override
    public List<Engagement> findByStatus(SubmissionStatus status) {
        List<Engagement> results = new ArrayList<>();
        String cursor = null;
        do {
            Page<Engagement> page = findByStatus(status, MAX_PAGE_SIZE, cursor);
            results.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);
        return List.copyOf(results);
    }

    @Override
    public Page<Engagement> findByStatus(SubmissionStatus status, int pageSize, String cursor) {
        Objects.requireNonNull(status, "status must not be null");
        validatePageSize(pageSize);
        QueryRequest.Builder query = QueryRequest.builder()
                .tableName(tableName)
                .indexName(STATUS_CREATED_AT_INDEX)
                .keyConditionExpression("#status = :status")
                .filterExpression("#entityType = :entityType")
                .expressionAttributeNames(Map.of(
                        "#status", DynamoDbRecordMapper.GSI1PK,
                        "#entityType", "entityType"
                ))
                .expressionAttributeValues(Map.of(
                        ":status", DynamoDbRecordMapper.string("STATUS#" + status.name()),
                        ":entityType", DynamoDbRecordMapper.string("ENGAGEMENT")
                ))
                .scanIndexForward(false)
                .limit(pageSize);
        if (cursor != null) {
            query.exclusiveStartKey(DynamoDbPageCursor.decode(cursor));
        }
        QueryResponse response = dynamoDbClient.query(query.build());
        List<Engagement> items = response.items().stream().map(mapper::toEngagement).toList();
        return new Page<>(items, DynamoDbPageCursor.encode(response.lastEvaluatedKey()));
    }

    @Override
    public Page<EngagementQueueEntry> findQueue(
            SubmissionStatus status,
            Instant fromInclusive,
            Instant toExclusive,
            int pageSize,
            String cursor
    ) {
        Objects.requireNonNull(status, "status must not be null");
        validatePageSize(pageSize);
        if (fromInclusive != null && toExclusive != null && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("from must be before to.");
        }
        String fromKey = fromInclusive == null ? "CREATED_AT#" : DynamoDbRecordMapper.createdAtPrefix(fromInclusive);
        String toKey = toExclusive == null
                ? "CREATED_AT#\uffff"
                : DynamoDbRecordMapper.createdAtPrefix(toExclusive);
        QueryRequest.Builder query = QueryRequest.builder()
                .tableName(tableName)
                .indexName(STATUS_CREATED_AT_INDEX)
                .keyConditionExpression("#status = :status AND #statusSort BETWEEN :from AND :to")
                .filterExpression("#entityType = :entityType")
                .expressionAttributeNames(Map.of(
                        "#status", DynamoDbRecordMapper.GSI1PK,
                        "#statusSort", DynamoDbRecordMapper.GSI1SK,
                        "#entityType", "entityType"
                ))
                .expressionAttributeValues(Map.of(
                        ":status", DynamoDbRecordMapper.string("STATUS#" + status.name()),
                        ":from", DynamoDbRecordMapper.string(fromKey),
                        ":to", DynamoDbRecordMapper.string(toKey),
                        ":entityType", DynamoDbRecordMapper.string("ENGAGEMENT")
                ))
                .scanIndexForward(false)
                .limit(pageSize);
        if (cursor != null) {
            Map<String, AttributeValue> key = DynamoDbPageCursor.decode(cursor);
            validateQueueCursor(key, status, fromKey, toKey);
            query.exclusiveStartKey(key);
        }
        QueryRequest request = query.build();
        List<EngagementQueueEntry> items = new ArrayList<>();
        QueryResponse response;
        do {
            response = dynamoDbClient.query(request);
            response.items().stream().map(mapper::toEngagementQueueEntry).forEach(items::add);
            if (items.size() < pageSize && response.lastEvaluatedKey() != null
                    && !response.lastEvaluatedKey().isEmpty()) {
                request = request.toBuilder().exclusiveStartKey(response.lastEvaluatedKey()).build();
            } else {
                break;
            }
        } while (true);
        return new Page<>(
                items.size() <= pageSize ? items : items.subList(0, pageSize),
                DynamoDbPageCursor.encode(response.lastEvaluatedKey())
        );
    }

    @Override
    public ReviewResult review(
            String engagementId,
            SubmissionStatus newStatus,
            String reviewerId,
            Instant reviewedAt
    ) {
        String normalizedId = requireNonBlank(engagementId, "engagementId");
        if (newStatus != SubmissionStatus.APPROVED && newStatus != SubmissionStatus.REJECTED) {
            throw new IllegalArgumentException("A review status must be APPROVED or REJECTED.");
        }
        String normalizedReviewer = requireNonBlank(reviewerId, "reviewerId");
        Objects.requireNonNull(reviewedAt, "reviewedAt must not be null");

        QueryResponse lookup = dynamoDbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :engagementPrefix)")
                .expressionAttributeNames(Map.of(
                        "#pk", DynamoDbRecordMapper.PK,
                        "#sk", DynamoDbRecordMapper.SK
                ))
                .expressionAttributeValues(Map.of(
                        ":pk", DynamoDbRecordMapper.string("ENTITY#" + normalizedId),
                        ":engagementPrefix", DynamoDbRecordMapper.string("TYPE#ENGAGEMENT#")
                ))
                .consistentRead(true)
                .limit(1)
                .build());
        if (lookup.items().isEmpty()) {
            return ReviewResult.NOT_FOUND;
        }

        Map<String, AttributeValue> engagement = lookup.items().getFirst();
        String currentStatus = requiredAttribute(engagement, "status");
        if (!SubmissionStatus.PENDING_REVIEW.name().equals(currentStatus)) {
            return ReviewResult.INVALID_TRANSITION;
        }
        String previousStatus = currentStatus;
        Map<String, AttributeValue> key = Map.of(
                DynamoDbRecordMapper.PK, requiredAttributeValue(engagement, DynamoDbRecordMapper.PK),
                DynamoDbRecordMapper.SK, requiredAttributeValue(engagement, DynamoDbRecordMapper.SK)
        );
        String reviewedAtValue = reviewedAt.toString();
        String reviewId = UUID.randomUUID().toString();
        Map<String, AttributeValue> auditItem = Map.of(
                DynamoDbRecordMapper.PK, DynamoDbRecordMapper.string("ENTITY#" + normalizedId),
                DynamoDbRecordMapper.SK, DynamoDbRecordMapper.string(
                        "REVIEW#" + reviewedAtValue + "#" + reviewId
                ),
                "recordId", DynamoDbRecordMapper.string(reviewId),
                "entityType", DynamoDbRecordMapper.string("ENGAGEMENT_REVIEW"),
                "engagementId", DynamoDbRecordMapper.string(normalizedId),
                "previousStatus", DynamoDbRecordMapper.string(previousStatus),
                "status", DynamoDbRecordMapper.string(newStatus.name()),
                "reviewerId", DynamoDbRecordMapper.string(normalizedReviewer),
                "reviewedAt", DynamoDbRecordMapper.string(reviewedAtValue)
        );
        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            TransactWriteItem.builder().update(Update.builder()
                                    .tableName(tableName)
                                    .key(key)
                                    .updateExpression("SET #status = :newStatus, #statusKey = :newStatusKey, "
                                            + "#reviewedAt = :reviewedAt, #reviewedBy = :reviewedBy")
                                    .conditionExpression("#status = :pending AND #entityType = :entityType")
                                    .expressionAttributeNames(Map.of(
                                            "#status", "status",
                                            "#statusKey", DynamoDbRecordMapper.GSI1PK,
                                            "#reviewedAt", "reviewedAt",
                                            "#reviewedBy", "reviewedBy",
                                            "#entityType", "entityType"
                                    ))
                                    .expressionAttributeValues(Map.of(
                                            ":newStatus", DynamoDbRecordMapper.string(newStatus.name()),
                                            ":newStatusKey", DynamoDbRecordMapper.string(
                                                    "STATUS#" + newStatus.name()
                                            ),
                                            ":reviewedAt", DynamoDbRecordMapper.string(reviewedAtValue),
                                            ":reviewedBy", DynamoDbRecordMapper.string(normalizedReviewer),
                                            ":pending", DynamoDbRecordMapper.string(
                                                    SubmissionStatus.PENDING_REVIEW.name()
                                            ),
                                            ":entityType", DynamoDbRecordMapper.string("ENGAGEMENT")
                                    ))
                                    .build()).build(),
                            TransactWriteItem.builder().put(software.amazon.awssdk.services.dynamodb.model.Put
                                    .builder()
                                    .tableName(tableName)
                                    .item(auditItem)
                                    .conditionExpression("attribute_not_exists(#pk)")
                                    .expressionAttributeNames(Map.of("#pk", DynamoDbRecordMapper.PK))
                                    .build()).build()
                    )
                    .build());
            return ReviewResult.UPDATED;
        } catch (TransactionCanceledException canceled) {
            if (canceled.cancellationReasons() != null && canceled.cancellationReasons().stream()
                    .anyMatch(reason -> "ConditionalCheckFailed".equals(reason.code()))) {
                return ReviewResult.INVALID_TRANSITION;
            }
            throw canceled;
        }
    }

    @Override
    public List<Engagement> findByAreaCodeAndStatus(String areaCode, SubmissionStatus status) {
        String normalizedAreaCode = requireNonBlank(areaCode, "areaCode");
        Objects.requireNonNull(status, "status must not be null");
        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .indexName(STATUS_CREATED_AT_INDEX)
                .keyConditionExpression("#status = :status")
                .filterExpression("#area = :area")
                .expressionAttributeNames(Map.of(
                        "#status", DynamoDbRecordMapper.GSI1PK,
                        "#area", "areaCode"
                ))
                .expressionAttributeValues(Map.of(
                        ":status", DynamoDbRecordMapper.string("STATUS#" + status.name()),
                        ":area", DynamoDbRecordMapper.string(normalizedAreaCode)
                ))
                .scanIndexForward(false)
                .build();
        return queryAll(request).stream().map(mapper::toEngagement).toList();
    }

    @Override
    public List<ChurchDirectoryRecord> findApprovedByAreaCode(String areaCode) {
        String normalizedAreaCode = requireNonBlank(areaCode, "areaCode").trim();
        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .indexName(AREA_CREATED_AT_INDEX)
                .keyConditionExpression("#area = :area")
                .filterExpression("#entityType = :entityType AND #status = :status")
                .expressionAttributeNames(Map.of(
                        "#area", DynamoDbRecordMapper.GSI2PK,
                        "#entityType", "entityType",
                        "#status", "status"
                ))
                .expressionAttributeValues(Map.of(
                        ":area", DynamoDbRecordMapper.string("AREA#" + normalizedAreaCode),
                        ":entityType", DynamoDbRecordMapper.string("CHURCH_DIRECTORY"),
                        ":status", DynamoDbRecordMapper.string("APPROVED")
                ))
                .scanIndexForward(true)
                .build();
        return queryAll(request).stream().map(mapper::toChurchDirectoryRecord).toList();
    }

    @Override
    public List<DevotionLink> findPublished() {
        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .indexName(STATUS_CREATED_AT_INDEX)
                .keyConditionExpression("#statusKey = :statusKey")
                .filterExpression("#entityType = :entityType AND #status = :status")
                .expressionAttributeNames(Map.of(
                        "#statusKey", DynamoDbRecordMapper.GSI1PK,
                        "#entityType", "entityType",
                        "#status", "status"
                ))
                .expressionAttributeValues(Map.of(
                        ":statusKey", DynamoDbRecordMapper.string("STATUS#APPROVED"),
                        ":entityType", DynamoDbRecordMapper.string("DEVOTION_LINK"),
                        ":status", DynamoDbRecordMapper.string(DevotionLinkStatus.APPROVED.name())
                ))
                .scanIndexForward(false)
                .build();
        return queryAll(request).stream().map(mapper::toDevotionLink).toList();
    }

    private List<Map<String, AttributeValue>> queryAll(QueryRequest initialRequest) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        QueryRequest request = initialRequest;
        QueryResponse response;
        do {
            response = dynamoDbClient.query(request);
            items.addAll(response.items());
            if (response.lastEvaluatedKey() != null && !response.lastEvaluatedKey().isEmpty()) {
                request = initialRequest.toBuilder()
                        .exclusiveStartKey(response.lastEvaluatedKey())
                        .build();
            }
        } while (response.lastEvaluatedKey() != null && !response.lastEvaluatedKey().isEmpty());
        return items;
    }

    private static void validatePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
    }

    private static void validateQueueCursor(
            Map<String, AttributeValue> key,
            SubmissionStatus status,
            String fromKey,
            String toKey
    ) {
        String cursorStatus = requiredAttribute(key, DynamoDbRecordMapper.GSI1PK);
        String cursorSort = requiredAttribute(key, DynamoDbRecordMapper.GSI1SK);
        if (!("STATUS#" + status.name()).equals(cursorStatus)
                || cursorSort.compareTo(fromKey) < 0 || cursorSort.compareTo(toKey) > 0) {
            throw new IllegalArgumentException("Invalid DynamoDB pagination cursor.");
        }
    }

    private static AttributeValue requiredAttributeValue(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.s() == null || value.s().isBlank()) {
            throw new IllegalStateException("DynamoDB item is missing required key: " + name);
        }
        return value;
    }

    private static boolean optionalBoolean(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value != null && Boolean.TRUE.equals(value.bool());
    }

    private static String requiredAttribute(Map<String, AttributeValue> item, String name) {
        return requiredAttributeValue(item, name).s();
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank.");
        }
        return value;
    }
}
