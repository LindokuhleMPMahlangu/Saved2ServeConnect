package com.saved2serve.persistence.dynamodb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class DynamoDbPageCursor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() { };
    private static final Set<String> REQUIRED_KEYS = Set.of(
            DynamoDbRecordMapper.PK,
            DynamoDbRecordMapper.SK,
            DynamoDbRecordMapper.GSI1PK,
            DynamoDbRecordMapper.GSI1SK
    );

    private DynamoDbPageCursor() {
    }

    static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        Map<String, String> values = lastEvaluatedKey.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    String value = entry.getValue().s();
                    if (value == null) {
                        throw new IllegalStateException("DynamoDB pagination keys must be strings.");
                    }
                    return value;
                }, (first, second) -> first, TreeMap::new));
        validateKey(values);
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(OBJECT_MAPPER.writeValueAsBytes(values));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode the DynamoDB pagination cursor.", exception);
        }
    }

    static Map<String, AttributeValue> decode(String cursor) {
        if (cursor == null) {
            return Map.of();
        }
        if (cursor.isBlank() || cursor.length() > 4096) {
            throw new IllegalArgumentException("Invalid DynamoDB pagination cursor.");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            Map<String, String> values = OBJECT_MAPPER.readValue(decoded, MAP_TYPE);
            validateKey(values);
            return values.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> DynamoDbRecordMapper.string(entry.getValue())
                    ));
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid DynamoDB pagination cursor.", exception);
        }
    }

    private static void validateKey(Map<String, String> values) {
        if (values == null || !values.keySet().containsAll(REQUIRED_KEYS)
                || values.values().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Invalid DynamoDB pagination key.");
        }
    }
}
