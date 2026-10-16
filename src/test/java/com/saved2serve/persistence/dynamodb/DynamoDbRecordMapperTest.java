package com.saved2serve.persistence.dynamodb;

import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.EngagementType;
import com.saved2serve.domain.ChurchDirectoryRecord;
import com.saved2serve.domain.ChurchDirectoryStatus;
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
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
    private static final Instant VERIFIED_AT = Instant.parse("2026-10-25T10:00:00Z");

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
        assertFalse(item.containsKey("name"));
        assertFalse(item.containsKey("phone"));
        assertFalse(item.containsKey("message"));
        assertTrue(item.containsKey("privateContentCiphertext"));
        assertEquals("STATUS#PENDING_REVIEW", item.get("GSI1PK").s());
        assertTrue(item.get("GSI1SK").s().startsWith("CREATED_AT#"));
        assertEquals(engagement, restored);

        ArgumentCaptor<EncryptRequest> encryptRequests = ArgumentCaptor.forClass(EncryptRequest.class);
        verify(kmsClient, org.mockito.Mockito.times(2)).encrypt(encryptRequests.capture());
        assertEquals(Map.of(
                "recordId", item.get("recordId").s(),
                "entityType", "ENGAGEMENT",
                "attribute", "privateContent"
        ), encryptRequests.getAllValues().get(1).encryptionContext());
    }

    @Test
    void rejectsPrivateContentAboveKmsPlaintextLimitBeforeEncrypting() {
        KmsClient kmsClient = mockKmsClient();
        DynamoDbRecordMapper mapper = new DynamoDbRecordMapper(kmsClient, "alias/test-email-key");
        Engagement engagement = new Engagement(
                "A Person",
                EMAIL,
                "10001",
                "",
                true,
                EngagementType.HELP_REQUEST,
                "\uD83D\uDE4F".repeat(1500),
                SubmissionStatus.PENDING_REVIEW
        );

        assertThrows(IllegalArgumentException.class, () -> mapper.toItem(engagement));
        verify(kmsClient, never()).encrypt(any(EncryptRequest.class));
    }

    @Test
    void mapsChurchDirectoryRecordWithInternalVerificationMetadata() {
        KmsClient kmsClient = mockKmsClient();
        DynamoDbRecordMapper mapper = new DynamoDbRecordMapper(kmsClient, "alias/test-email-key");
        Map<String, AttributeValue> item = Map.of(
                "churchId", string("church-123"),
                "displayName", string("Example Church"),
                "publicWebsite", string("https://example.org"),
                "publicAddress", string("14 Example Road"),
                "areaCode", string("00123"),
                "status", string("APPROVED"),
                "verifiedAt", string(VERIFIED_AT.toString()),
                "sourceReference", string("internal-review-source")
        );

        ChurchDirectoryRecord record = mapper.toChurchDirectoryRecord(item);

        assertEquals(new ChurchDirectoryRecord(
                "church-123",
                "Example Church",
                "https://example.org",
                "00123",
                ChurchDirectoryStatus.APPROVED,
                VERIFIED_AT,
                "internal-review-source",
                "14 Example Road"
        ), record);
        verify(kmsClient, never()).decrypt(any(DecryptRequest.class));
    }

    @Test
    void approvedChurchMappingPersistsVerificationMetadataAndAreaIndexKeys() {
        DynamoDbRecordMapper mapper = new DynamoDbRecordMapper(mockKmsClient(), "alias/test-email-key");
        ChurchDirectoryRecord church = new ChurchDirectoryRecord(
                "church-123",
                "Example Church",
                "https://example.org",
                "00123",
                ChurchDirectoryStatus.APPROVED,
                VERIFIED_AT,
                "internal-review-source",
                "14 Example Road"
        );

        Map<String, AttributeValue> item = mapper.toItem(church);
        ChurchDirectoryRecord restored = mapper.toChurchDirectoryRecord(item);

        assertEquals("CHURCH#church-123", item.get("PK").s());
        assertEquals("AREA#00123", item.get("SK").s());
        assertEquals("AREA#00123", item.get("GSI2PK").s());
        assertEquals("CHURCH#church-123#AREA#00123", item.get("GSI2SK").s());
        assertEquals(VERIFIED_AT.toString(), item.get("verifiedAt").s());
        assertEquals("internal-review-source", item.get("sourceReference").s());
        assertEquals("14 Example Road", item.get("publicAddress").s());
        assertEquals(church, restored);
    }

    @Test
    void pendingChurchCannotBeSeededAsApproved() {
        DynamoDbRecordMapper mapper = new DynamoDbRecordMapper(mockKmsClient(), "alias/test-email-key");
        ChurchDirectoryRecord church = new ChurchDirectoryRecord(
                "church-123",
                "Example Church",
                "https://example.org",
                "00123",
                ChurchDirectoryStatus.PENDING_REVIEW,
                null,
                "candidate-source"
        );

        assertThrows(IllegalArgumentException.class, () -> mapper.toItem(church));
    }
    private static KmsClient mockKmsClient() {
        KmsClient kmsClient = mock(KmsClient.class);
        when(kmsClient.encrypt(any(EncryptRequest.class))).thenReturn(EncryptResponse.builder()
                .ciphertextBlob(SdkBytes.fromByteArray(ENCRYPTED_EMAIL))
                .build());
        when(kmsClient.decrypt(any(DecryptRequest.class))).thenAnswer(invocation -> {
            DecryptRequest request = invocation.getArgument(0);
            byte[] plaintext = request.encryptionContext().containsKey("attribute")
                    ? privateContentPlaintext()
                    : EMAIL.getBytes(StandardCharsets.UTF_8);
            return DecryptResponse.builder()
                    .plaintext(SdkBytes.fromByteArray(plaintext))
                    .build();
        });
        return kmsClient;
    }

    private static byte[] privateContentPlaintext() throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("A Person");
            output.writeUTF("");
            output.writeUTF("Please contact me.");
            output.flush();
            return bytes.toByteArray();
        }
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }

}
