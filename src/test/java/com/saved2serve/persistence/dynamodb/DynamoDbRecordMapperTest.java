package com.saved2serve.persistence.dynamodb;

import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.EngagementType;
import com.saved2serve.domain.Interest;
import com.saved2serve.domain.SubmissionStatus;
import com.saved2serve.util.EmailHasher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoDbRecordMapperTest {
    private static final String EMAIL = "Person@Example.com";
    private static final byte[] ENCRYPTED_EMAIL = new byte[]{1, 2, 3, 4};
    private static final Instant CONSENTED_AT = Instant.parse("2026-10-24T10:15:30Z");

    @Test
    void interestMappingEncryptsEmailAndUsesHashOnlyForLookupKeys() {
        KmsClient kmsClient = mockKmsClient();
        DynamoDbRecordMapper mapper = new DynamoDbRecordMapper(kmsClient, "alias/test-email-key");

        Map<String, AttributeValue> item = mapper.toItem(new Interest(
                "A Person", EMAIL, "10001", "5551234567", true, "landing-page", CONSENTED_AT
        ));
        Interest restored = mapper.toInterest(item);

        assertFalse(item.containsKey("email"));
        assertEquals(EMAIL, restored.email());
        assertEquals(CONSENTED_AT, restored.consentedAt());
        assertEquals(CONSENTED_AT.toString(), item.get("consentedAt").s());
        assertEquals("INTEREST", item.get("entityType").s());
        assertTrue(item.get("createdAt").s().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{9}Z"));
        assertEquals("AREA#10001", item.get("GSI2PK").s());
        assertEquals("EMAIL_HASH#" + EmailHasher.sha256("person@example.com"), item.get("GSI3PK").s());
        assertEquals(ENCRYPTED_EMAIL.length, item.get("emailCiphertext").b().asByteArray().length);

        ArgumentCaptor<EncryptRequest> encryptRequests = ArgumentCaptor.forClass(EncryptRequest.class);
        verify(kmsClient).encrypt(encryptRequests.capture());
        assertEquals("alias/test-email-key", encryptRequests.getValue().keyId());
        assertEquals(EMAIL, encryptRequests.getValue().plaintext().asUtf8String());
        assertEquals(Map.of("recordId", item.get("recordId").s(), "entityType", "INTEREST"),
                encryptRequests.getValue().encryptionContext());
    }

    @Test
    void rejectsEmailExceedingKmsPlaintextLimit() {
        KmsClient kmsClient = mockKmsClient();
        DynamoDbRecordMapper mapper = new DynamoDbRecordMapper(kmsClient, "alias/test-email-key");
        Interest interest = new Interest(
                "A Person", "a".repeat(4097), "10001", "", true, "landing-page", CONSENTED_AT
        );

        assertThrows(IllegalArgumentException.class, () -> mapper.toItem(interest));
        verify(kmsClient, never()).encrypt(any(EncryptRequest.class));
    }

    @Test
    void idempotentInterestMappingUsesHashedKeyAndRequestFingerprint() {
        KmsClient kmsClient = mockKmsClient();
        DynamoDbRecordMapper mapper = new DynamoDbRecordMapper(kmsClient, "alias/test-email-key");

        Map<String, AttributeValue> item = mapper.toIdempotentItem(
                new Interest("A Person", EMAIL, "10001", "", true, "landing-page", CONSENTED_AT),
                "idempotency-hash",
                "request-fingerprint"
        );

        assertEquals("IDEMPOTENCY#idempotency-hash", item.get("PK").s());
        assertEquals("REGISTRATION", item.get("SK").s());
        assertEquals("idempotency-hash", item.get("idempotencyKeyHash").s());
        assertEquals("request-fingerprint", item.get("requestFingerprint").s());
        assertEquals(CONSENTED_AT.toString(), item.get("consentedAt").s());
        assertFalse(item.containsKey("idempotencyKey"));
        assertFalse(item.containsKey("email"));
        assertEquals("AREA#10001", item.get("GSI2PK").s());
        assertEquals("EMAIL_HASH#" + EmailHasher.sha256(EMAIL), item.get("GSI3PK").s());
    }

    @Test
    void engagementMappingIndexesStatusAndMapsBackToDomainRecord() {
        KmsClient kmsClient = mockKmsClient();
        DynamoDbRecordMapper mapper = new DynamoDbRecordMapper(kmsClient, "alias/test-email-key");
        Engagement engagement = new Engagement(
                "A Person", EMAIL, "10001", "", true, EngagementType.HELP_REQUEST,
                "Please contact me.", SubmissionStatus.PENDING_REVIEW
        );

        Map<String, AttributeValue> item = mapper.toItem(engagement);
        Engagement restored = mapper.toEngagement(item);

        assertFalse(item.containsKey("email"));
        assertEquals("STATUS#PENDING_REVIEW", item.get("GSI1PK").s());
        assertTrue(item.get("GSI1SK").s().startsWith("CREATED_AT#"));
        assertEquals(engagement, restored);
    }
    private static KmsClient mockKmsClient() {
        KmsClient kmsClient = mock(KmsClient.class);
        when(kmsClient.encrypt(any(EncryptRequest.class))).thenReturn(EncryptResponse.builder()
                .ciphertextBlob(SdkBytes.fromByteArray(ENCRYPTED_EMAIL))
                .build());
        when(kmsClient.decrypt(any(DecryptRequest.class))).thenReturn(DecryptResponse.builder()
                .plaintext(SdkBytes.fromString(EMAIL, StandardCharsets.UTF_8))
                .build());
        return kmsClient;
    }

}
