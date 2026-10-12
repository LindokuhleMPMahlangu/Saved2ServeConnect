package com.saved2serve.persistence.dynamodb;

import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.Interest;
import com.saved2serve.domain.SubmissionStatus;
import com.saved2serve.repository.Page;
import com.saved2serve.util.EmailHasher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoDbSaved2ServeRepositoryTest {
    @Test
    void areaCodeLookupUsesAreaIndexAndMapsEveryPage() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(kmsClient.decrypt(any(DecryptRequest.class))).thenReturn(DecryptResponse.builder()
                .plaintext(SdkBytes.fromUtf8String("person@example.com"))
                .build());
        Map<String, AttributeValue> firstPageKey = Map.of(
                "PK", string("ENTITY#first"),
                "SK", string("TYPE#INTEREST#2026-01-01T00:00:00Z"),
                "GSI2PK", string("AREA#10001"),
                "GSI2SK", string("CREATED_AT#2026-01-01T00:00:00Z#first")
        );
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(
                QueryResponse.builder().items(List.of(interestItem("first"))).lastEvaluatedKey(firstPageKey).build(),
                QueryResponse.builder().items(List.of(interestItem("second"))).build()
        );
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        List<Interest> results = repository.findByAreaCode("10001");

        assertEquals(List.of("first", "second"), results.stream().map(Interest::name).toList());
        ArgumentCaptor<QueryRequest> requests = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDbClient, org.mockito.Mockito.times(2)).query(requests.capture());
        QueryRequest firstRequest = requests.getAllValues().getFirst();
        assertEquals(DynamoDbSaved2ServeRepository.AREA_CREATED_AT_INDEX, firstRequest.indexName());
        assertEquals("#area = :area", firstRequest.keyConditionExpression());
        assertEquals("#entityType = :entityType", firstRequest.filterExpression());
        assertEquals("AREA#10001", firstRequest.expressionAttributeValues().get(":area").s());
        assertEquals("INTEREST", firstRequest.expressionAttributeValues().get(":entityType").s());
        assertFalse(firstRequest.scanIndexForward());
        assertEquals(firstPageKey, requests.getAllValues().get(1).exclusiveStartKey());
    }

    @Test
    void emailHashLookupUsesKeysOnlyCountQuery() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(dynamoDbClient.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().count(1).build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        assertTrue(repository.existsByEmailHash("normalized-hash"));

        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDbClient).query(request.capture());
        assertEquals(DynamoDbSaved2ServeRepository.EMAIL_HASH_INDEX, request.getValue().indexName());
        assertEquals(Select.COUNT, request.getValue().select());
        assertEquals(1, request.getValue().limit());
        assertEquals("EMAIL_HASH#normalized-hash",
                request.getValue().expressionAttributeValues().get(":emailHash").s());
    }

    @Test
    void statusQueueUsesExclusiveStartKeyToReturnFollowingPage() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(kmsClient.decrypt(any(DecryptRequest.class))).thenReturn(DecryptResponse.builder()
                .plaintext(SdkBytes.fromUtf8String("person@example.com"))
                .build());
        Map<String, AttributeValue> lastKey = Map.of(
                "PK", string("ENTITY#first"),
                "SK", string("TYPE#ENGAGEMENT#2026-01-01T00:00:00Z"),
                "GSI1PK", string("STATUS#PENDING_REVIEW"),
                "GSI1SK", string("CREATED_AT#2026-01-01T00:00:00Z#first")
        );
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(
                QueryResponse.builder().items(List.of(engagementItem("first"))).lastEvaluatedKey(lastKey).build(),
                QueryResponse.builder().items(List.of(engagementItem("second"))).build()
        );
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        Page<Engagement> firstPage = repository.findByStatus(SubmissionStatus.PENDING_REVIEW, 1, null);
        Page<Engagement> secondPage = repository.findByStatus(
                SubmissionStatus.PENDING_REVIEW, 1, firstPage.nextCursor()
        );

        assertEquals("first", firstPage.items().getFirst().name());
        assertNotNull(firstPage.nextCursor());
        assertEquals("second", secondPage.items().getFirst().name());
        assertNull(secondPage.nextCursor());

        ArgumentCaptor<QueryRequest> requests = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDbClient, org.mockito.Mockito.times(2)).query(requests.capture());
        assertEquals(DynamoDbSaved2ServeRepository.STATUS_CREATED_AT_INDEX, requests.getAllValues().getFirst().indexName());
        assertEquals(Map.of(), requests.getAllValues().getFirst().exclusiveStartKey());
        assertEquals(lastKey, requests.getAllValues().get(1).exclusiveStartKey());
        QueryRequest firstRequest = requests.getAllValues().getFirst();
        assertEquals("#status = :status", firstRequest.keyConditionExpression());
        assertEquals("STATUS#PENDING_REVIEW",
                firstRequest.expressionAttributeValues().get(":status").s());
        assertEquals(1, firstRequest.limit());
        assertFalse(firstRequest.scanIndexForward());
    }

    @Test
    void emptyStatusQueryReturnsEmptyPage() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        Page<Engagement> page = repository.findByStatus(SubmissionStatus.PENDING_REVIEW, 10, null);

        assertEquals(List.of(), page.items());
        assertNull(page.nextCursor());
    }

    @Test
    void rejectsInvalidPageSizeBeforeCallingDynamoDb() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, mock(KmsClient.class), "Saved2ServeRecords", "alias/test-email-key"
        );

        assertThrows(IllegalArgumentException.class,
                () -> repository.findByStatus(SubmissionStatus.PENDING_REVIEW, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> repository.findByStatus(SubmissionStatus.PENDING_REVIEW, 101, null));
        verify(dynamoDbClient, never()).query(any(QueryRequest.class));
    }

    @Test
    void dynamoDbQueryFailureIsPropagated() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        AwsServiceException awsFailure = AwsServiceException.builder()
                .message("Unavailable").statusCode(503).build();
        when(dynamoDbClient.query(any(QueryRequest.class))).thenThrow(awsFailure);
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, mock(KmsClient.class), "Saved2ServeRecords", "alias/test-email-key"
        );

        AwsServiceException thrown = assertThrows(AwsServiceException.class,
                () -> repository.findByStatus(SubmissionStatus.PENDING_REVIEW, 10, null));

        assertSame(awsFailure, thrown);
    }

    @Test
    void kmsEncryptionFailurePreventsWriteAndIsPropagated() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        AwsServiceException awsFailure = AwsServiceException.builder()
                .message("Access denied").statusCode(403).build();
        when(kmsClient.encrypt(any(EncryptRequest.class))).thenThrow(awsFailure);
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        AwsServiceException thrown = assertThrows(AwsServiceException.class,
                () -> repository.save(new Interest(
                        "A Person", "person@example.com", "10001", "", true, "landing-page"
                )));

        assertSame(awsFailure, thrown);
        verify(dynamoDbClient, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void dynamoDbWriteFailureIsPropagated() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(kmsClient.encrypt(any(EncryptRequest.class))).thenReturn(EncryptResponse.builder()
                .ciphertextBlob(SdkBytes.fromUtf8String("encrypted"))
                .build());
        AwsServiceException awsFailure = AwsServiceException.builder()
                .message("Unavailable").statusCode(503).build();
        when(dynamoDbClient.putItem(any(PutItemRequest.class))).thenThrow(awsFailure);
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        AwsServiceException thrown = assertThrows(AwsServiceException.class,
                () -> repository.save(new Interest(
                        "A Person", "person@example.com", "10001", "", true, "landing-page"
                )));

        assertSame(awsFailure, thrown);
    }

    @Test
    void kmsDecryptionFailureIsPropagatedWhenMappingResults() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        AwsServiceException awsFailure = AwsServiceException.builder()
                .message("Access denied").statusCode(403).build();
        when(kmsClient.decrypt(any(DecryptRequest.class))).thenThrow(awsFailure);
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(
                QueryResponse.builder().items(List.of(engagementItem("first"))).build()
        );
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        AwsServiceException thrown = assertThrows(AwsServiceException.class,
                () -> repository.findByStatus(SubmissionStatus.PENDING_REVIEW, 10, null));

        assertSame(awsFailure, thrown);
    }

    @Test
    void savePersistsEncryptedEmailAndHashIndexAttributes() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(kmsClient.encrypt(any(EncryptRequest.class))).thenReturn(EncryptResponse.builder()
                .ciphertextBlob(SdkBytes.fromUtf8String("encrypted"))
                .build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        repository.save(new Interest(
                "A Person", "person@example.com", "10001", "", true, "landing-page"
        ));

        ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDbClient).putItem(request.capture());
        assertEquals("Saved2ServeRecords", request.getValue().tableName());
        assertEquals("EMAIL_HASH#" + EmailHasher.sha256("person@example.com"),
                request.getValue().item().get("GSI3PK").s());
        assertFalse(request.getValue().item().containsKey("email"));
        assertEquals("encrypted", request.getValue().item().get("emailCiphertext").b().asUtf8String());
    }

    private static Map<String, AttributeValue> engagementItem(String id) {
        return Map.ofEntries(
                Map.entry("PK", string("ENTITY#" + id)),
                Map.entry("SK", string("TYPE#ENGAGEMENT#2026-01-01T00:00:00Z")),
                Map.entry("recordId", string(id)),
                Map.entry("entityType", string("ENGAGEMENT")),
                Map.entry("createdAt", string("2026-01-01T00:00:00Z")),
                Map.entry("areaCode", string("10001")),
                Map.entry("emailHash", string("hash")),
                Map.entry("emailCiphertext", AttributeValue.builder().b(SdkBytes.fromUtf8String("encrypted")).build()),
                Map.entry("name", string(id)),
                Map.entry("phone", string("")),
                Map.entry("consent", AttributeValue.builder().bool(true).build()),
                Map.entry("type", string("HELP_REQUEST")),
                Map.entry("message", string("Please contact me.")),
                Map.entry("status", string("PENDING_REVIEW")),
                Map.entry("GSI1PK", string("STATUS#PENDING_REVIEW")),
                Map.entry("GSI1SK", string("CREATED_AT#2026-01-01T00:00:00Z#" + id)),
                Map.entry("GSI2PK", string("AREA#10001")),
                Map.entry("GSI2SK", string("CREATED_AT#2026-01-01T00:00:00Z#" + id)),
                Map.entry("GSI3PK", string("EMAIL_HASH#hash")),
                Map.entry("GSI3SK", string("ENTITY#" + id))
        );
    }

    private static Map<String, AttributeValue> interestItem(String id) {
        return Map.ofEntries(
                Map.entry("PK", string("ENTITY#" + id)),
                Map.entry("SK", string("TYPE#INTEREST#2026-01-01T00:00:00Z")),
                Map.entry("recordId", string(id)),
                Map.entry("entityType", string("INTEREST")),
                Map.entry("createdAt", string("2026-01-01T00:00:00Z")),
                Map.entry("areaCode", string("10001")),
                Map.entry("emailHash", string("hash")),
                Map.entry("emailCiphertext", AttributeValue.builder().b(SdkBytes.fromUtf8String("encrypted")).build()),
                Map.entry("name", string(id)),
                Map.entry("phone", string("")),
                Map.entry("consent", AttributeValue.builder().bool(true).build()),
                Map.entry("source", string("landing-page")),
                Map.entry("GSI2PK", string("AREA#10001")),
                Map.entry("GSI2SK", string("CREATED_AT#2026-01-01T00:00:00Z#" + id)),
                Map.entry("GSI3PK", string("EMAIL_HASH#hash")),
                Map.entry("GSI3SK", string("ENTITY#" + id))
        );
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
