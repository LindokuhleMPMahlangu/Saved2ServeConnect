package com.saved2serve.persistence.dynamodb;

import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.EngagementType;
import com.saved2serve.domain.Interest;
import com.saved2serve.domain.SubmissionStatus;
import com.saved2serve.util.EmailHasher;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class DynamoDbRecordMapper {
    static final String PK = "PK";
    static final String SK = "SK";
    static final String GSI1PK = "GSI1PK";
    static final String GSI1SK = "GSI1SK";
    static final String GSI2PK = "GSI2PK";
    static final String GSI2SK = "GSI2SK";
    static final String GSI3PK = "GSI3PK";
    static final String GSI3SK = "GSI3SK";
    private static final DateTimeFormatter CREATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final int KMS_MAX_PLAINTEXT_BYTES = 4096;

    private final KmsClient kmsClient;
    private final String kmsKeyId;

    public DynamoDbRecordMapper(KmsClient kmsClient, String kmsKeyId) {
        this.kmsClient = Objects.requireNonNull(kmsClient, "kmsClient must not be null");
        this.kmsKeyId = requireNonBlank(kmsKeyId, "kmsKeyId");
    }

    public Map<String, AttributeValue> toItem(Interest interest) {
        Objects.requireNonNull(interest, "interest must not be null");
        String recordId = UUID.randomUUID().toString();
        String createdAt = CREATED_AT_FORMAT.format(Instant.now());
        Map<String, AttributeValue> item = baseItem(recordId, "INTEREST", createdAt, interest.areaCode(), interest.email());
        item.put("name", string(interest.name()));
        item.put("phone", string(interest.phone()));
        item.put("consent", bool(interest.consent()));
        item.put("source", string(interest.source()));
        return Map.copyOf(item);
    }

    public Map<String, AttributeValue> toItem(Engagement engagement) {
        Objects.requireNonNull(engagement, "engagement must not be null");
        String recordId = UUID.randomUUID().toString();
        String createdAt = CREATED_AT_FORMAT.format(Instant.now());
        Map<String, AttributeValue> item = baseItem(recordId, "ENGAGEMENT", createdAt, engagement.areaCode(), engagement.email());
        String status = engagement.status().name();
        item.put("name", string(engagement.name()));
        item.put("phone", string(engagement.phone()));
        item.put("consent", bool(engagement.consent()));
        item.put("type", string(engagement.type().name()));
        item.put("message", string(engagement.message()));
        item.put("status", string(status));
        item.put(GSI1PK, string("STATUS#" + status));
        item.put(GSI1SK, string(createdAtKey(createdAt, recordId)));
        return Map.copyOf(item);
    }

    public Interest toInterest(Map<String, AttributeValue> item) {
        String recordId = requiredString(item, "recordId");
        return new Interest(
                requiredString(item, "name"),
                decryptEmail(item, recordId, "INTEREST"),
                requiredString(item, "areaCode"),
                requiredString(item, "phone"),
                requiredBoolean(item, "consent"),
                requiredString(item, "source")
        );
    }

    public Engagement toEngagement(Map<String, AttributeValue> item) {
        String recordId = requiredString(item, "recordId");
        return new Engagement(
                requiredString(item, "name"),
                decryptEmail(item, recordId, "ENGAGEMENT"),
                requiredString(item, "areaCode"),
                requiredString(item, "phone"),
                requiredBoolean(item, "consent"),
                EngagementType.valueOf(requiredString(item, "type")),
                requiredString(item, "message"),
                SubmissionStatus.valueOf(requiredString(item, "status"))
        );
    }

    private Map<String, AttributeValue> baseItem(
            String recordId,
            String entityType,
            String createdAt,
            String areaCode,
            String email
    ) {
        String emailHash = EmailHasher.sha256(email);
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, string("ENTITY#" + recordId));
        item.put(SK, string("TYPE#" + entityType + "#" + createdAt));
        item.put("recordId", string(recordId));
        item.put("entityType", string(entityType));
        item.put("createdAt", string(createdAt));
        item.put("areaCode", string(areaCode));
        item.put("emailHash", string(emailHash));
        item.put("emailCiphertext", binary(encryptEmail(email, recordId, entityType)));
        item.put(GSI2PK, string("AREA#" + areaCode));
        item.put(GSI2SK, string(createdAtKey(createdAt, recordId)));
        item.put(GSI3PK, string("EMAIL_HASH#" + emailHash));
        item.put(GSI3SK, string("ENTITY#" + recordId));
        return item;
    }

    private byte[] encryptEmail(String email, String recordId, String entityType) {
        byte[] plaintext = email.getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > KMS_MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("Email exceeds the AWS KMS plaintext limit.");
        }
        return kmsClient.encrypt(EncryptRequest.builder()
                        .keyId(kmsKeyId)
                        .plaintext(SdkBytes.fromByteArray(plaintext))
                        .encryptionContext(encryptionContext(recordId, entityType))
                        .build())
                .ciphertextBlob()
                .asByteArray();
    }

    private String decryptEmail(Map<String, AttributeValue> item, String recordId, String entityType) {
        byte[] ciphertext = item.get("emailCiphertext") == null
                ? null
                : item.get("emailCiphertext").b().asByteArray();
        if (ciphertext == null || ciphertext.length == 0) {
            throw new IllegalStateException("DynamoDB item is missing email ciphertext.");
        }
        return kmsClient.decrypt(DecryptRequest.builder()
                        .ciphertextBlob(SdkBytes.fromByteArray(ciphertext))
                        .encryptionContext(encryptionContext(recordId, entityType))
                        .build())
                .plaintext()
                .asString(StandardCharsets.UTF_8);
    }

    private static Map<String, String> encryptionContext(String recordId, String entityType) {
        return Map.of("recordId", recordId, "entityType", entityType);
    }

    static String createdAtKey(String createdAt, String recordId) {
        return "CREATED_AT#" + createdAt + "#" + recordId;
    }

    static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue binary(byte[] value) {
        return AttributeValue.builder().b(SdkBytes.fromByteArray(value)).build();
    }

    private static AttributeValue bool(boolean value) {
        return AttributeValue.builder().bool(value).build();
    }

    private static String requiredString(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.s() == null) {
            throw new IllegalStateException("DynamoDB item is missing required attribute: " + name);
        }
        return value.s();
    }

    private static boolean requiredBoolean(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || value.bool() == null) {
            throw new IllegalStateException("DynamoDB item is missing required attribute: " + name);
        }
        return value.bool();
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank.");
        }
        return value;
    }
}
