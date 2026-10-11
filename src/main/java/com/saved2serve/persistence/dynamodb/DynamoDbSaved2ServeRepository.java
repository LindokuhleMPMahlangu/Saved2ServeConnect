package com.saved2serve.persistence.dynamodb;

import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.Interest;
import com.saved2serve.domain.SubmissionStatus;
import com.saved2serve.repository.EngagementRepository;
import com.saved2serve.repository.InterestRepository;
import com.saved2serve.repository.Page;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.kms.KmsClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DynamoDbSaved2ServeRepository implements InterestRepository, EngagementRepository {
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
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(mapper.toItem(engagement))
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
                .expressionAttributeNames(Map.of("#status", DynamoDbRecordMapper.GSI1PK))
                .expressionAttributeValues(Map.of(
                        ":status", DynamoDbRecordMapper.string("STATUS#" + status.name())
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

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank.");
        }
        return value;
    }
}
