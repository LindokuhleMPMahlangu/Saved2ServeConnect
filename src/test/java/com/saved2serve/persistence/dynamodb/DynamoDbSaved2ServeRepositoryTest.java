package com.saved2serve.persistence.dynamodb;

import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.EngagementType;
import com.saved2serve.domain.FollowUpRecordType;
import com.saved2serve.domain.ChurchDirectoryRecord;
import com.saved2serve.domain.ChurchDirectoryStatus;
import com.saved2serve.domain.Interest;
import com.saved2serve.domain.SubmissionStatus;
import com.saved2serve.repository.InterestRepository;
import com.saved2serve.repository.EngagementQueueEntry;
import com.saved2serve.repository.EngagementRepository;
import com.saved2serve.repository.FollowUpRecipient;
import com.saved2serve.repository.Page;
import com.saved2serve.util.EmailHasher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;

import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoDbSaved2ServeRepositoryTest {
    private static final Instant CONSENTED_AT = Instant.parse("2026-10-24T10:15:30Z");
    private static final Instant CHURCH_VERIFIED_AT = Instant.parse("2026-10-25T10:00:00Z");

    @Test
    void savesNewEngagementAsPrivatePendingReviewItem() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(kmsClient.encrypt(any(EncryptRequest.class))).thenReturn(EncryptResponse.builder()
                .ciphertextBlob(SdkBytes.fromUtf8String("encrypted-email"))
                .build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );
        Engagement engagement = new Engagement(
                "Alex Smith",
                "alex@example.com",
                "1800",
                "5551234567",
                true,
                EngagementType.HELP_REQUEST,
                "Please contact me.",
                SubmissionStatus.PENDING_REVIEW
        );

        repository.save(engagement);

        ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDbClient).putItem(request.capture());
        Map<String, AttributeValue> item = request.getValue().item();
        assertEquals("Saved2ServeRecords", request.getValue().tableName());
        assertEquals("ENGAGEMENT", item.get("entityType").s());
        assertEquals("PENDING_REVIEW", item.get("status").s());
        assertEquals("STATUS#PENDING_REVIEW", item.get("GSI1PK").s());
        assertEquals("AREA#1800", item.get("GSI2PK").s());
        assertEquals("EMAIL_HASH#" + EmailHasher.sha256("alex@example.com"), item.get("GSI3PK").s());
        assertTrue(item.containsKey("emailCiphertext"));
        assertTrue(item.containsKey("privateContentCiphertext"));
        assertFalse(item.containsKey("email"));
        assertFalse(item.containsKey("name"));
        assertFalse(item.containsKey("phone"));
        assertFalse(item.containsKey("message"));
        assertFalse(item.containsKey("fullAddress"));
        assertFalse(item.containsKey("latitude"));
        assertFalse(item.containsKey("longitude"));
    }

    @Test
    void doesNotAllowSavingReviewedEngagementThroughSubmissionRepository() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, mock(KmsClient.class), "Saved2ServeRecords", "alias/test-email-key"
        );
        Engagement approved = new Engagement(
                "Alex Smith",
                "alex@example.com",
                "1800",
                "",
                true,
                EngagementType.HELP_REQUEST,
                "Please contact me.",
                SubmissionStatus.APPROVED
        );

        assertThrows(IllegalArgumentException.class, () -> repository.save(approved));
        verify(dynamoDbClient, never()).putItem(any(PutItemRequest.class));
    }

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
    void approvedChurchLookupUsesExactAreaAndFiltersToApprovedChurchRecords() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(List.of(Map.of(
                        "churchId", string("church-123"),
                        "displayName", string("Example Church"),
                        "publicWebsite", string("https://example.org"),
                        "areaCode", string("00123"),
                        "status", string("APPROVED"),
                        "verifiedAt", string(CHURCH_VERIFIED_AT.toString()),
                        "sourceReference", string("internal-review-source"),
                        "entityType", string("CHURCH_DIRECTORY")
                )))
                .build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        var results = repository.findApprovedByAreaCode(" 00123 ");

        assertEquals(1, results.size());
        assertEquals("00123", results.getFirst().areaCode());
        assertEquals(ChurchDirectoryStatus.APPROVED, results.getFirst().status());
        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDbClient).query(request.capture());
        assertEquals(DynamoDbSaved2ServeRepository.AREA_CREATED_AT_INDEX, request.getValue().indexName());
        assertEquals("#area = :area", request.getValue().keyConditionExpression());
        assertEquals("#entityType = :entityType AND #status = :status", request.getValue().filterExpression());
        assertEquals("AREA#00123", request.getValue().expressionAttributeValues().get(":area").s());
        assertEquals("CHURCH_DIRECTORY",
                request.getValue().expressionAttributeValues().get(":entityType").s());
        assertEquals("APPROVED", request.getValue().expressionAttributeValues().get(":status").s());
    }

    @Test
    void saveApprovedChurchPersistsDirectoryItem() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, mock(KmsClient.class), "Saved2ServeRecords", "alias/test-email-key"
        );
        ChurchDirectoryRecord church = new ChurchDirectoryRecord(
                "church-123",
                "Example Church",
                "https://example.org",
                "00123",
                ChurchDirectoryStatus.APPROVED,
                CHURCH_VERIFIED_AT,
                "internal-review-source"
        );

        repository.saveApproved(church);

        ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDbClient).putItem(request.capture());
        Map<String, AttributeValue> item = request.getValue().item();
        assertEquals("Saved2ServeRecords", request.getValue().tableName());
        assertEquals("CHURCH#church-123", item.get("PK").s());
        assertEquals("AREA#00123", item.get("SK").s());
        assertEquals("CHURCH_DIRECTORY", item.get("entityType").s());
        assertEquals("APPROVED", item.get("status").s());
        assertEquals(CHURCH_VERIFIED_AT.toString(), item.get("verifiedAt").s());
        assertEquals("internal-review-source", item.get("sourceReference").s());
        assertEquals("", item.get("publicAddress").s());
    }

    @Test
    void saveApprovedRejectsUnapprovedChurchWithoutWriting() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, mock(KmsClient.class), "Saved2ServeRecords", "alias/test-email-key"
        );
        ChurchDirectoryRecord pendingChurch = new ChurchDirectoryRecord(
                "church-123",
                "Example Church",
                "https://example.org",
                "00123",
                ChurchDirectoryStatus.PENDING_REVIEW,
                null,
                "candidate-source"
        );

        assertThrows(IllegalArgumentException.class, () -> repository.saveApproved(pendingChurch));
        verify(dynamoDbClient, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void approvedChurchLookupReturnsEmptyListWhenNoDirectoryRecordsMatch() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(dynamoDbClient.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        assertEquals(List.of(), repository.findApprovedByAreaCode("99999"));
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
    void engagementQueueFiltersByStatusAndTimeAndReturnsRecordMetadata() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(kmsClient.decrypt(any(DecryptRequest.class))).thenReturn(DecryptResponse.builder()
                .plaintext(SdkBytes.fromUtf8String("person@example.com"))
                .build());
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(List.of(engagementItem("first")))
                .build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-02T00:00:00Z");

        Page<EngagementQueueEntry> page = repository.findQueue(
                SubmissionStatus.PENDING_REVIEW, from, to, 10, null
        );

        assertEquals("first", page.items().getFirst().engagementId());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), page.items().getFirst().createdAt());
        assertEquals("first", page.items().getFirst().engagement().name());
        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDbClient).query(request.capture());
        assertEquals(DynamoDbSaved2ServeRepository.STATUS_CREATED_AT_INDEX, request.getValue().indexName());
        assertEquals("#status = :status AND #statusSort BETWEEN :from AND :to",
                request.getValue().keyConditionExpression());
        assertEquals("STATUS#PENDING_REVIEW",
                request.getValue().expressionAttributeValues().get(":status").s());
        assertEquals("CREATED_AT#2026-01-01T00:00:00.000000000Z",
                request.getValue().expressionAttributeValues().get(":from").s());
        assertEquals("CREATED_AT#2026-01-02T00:00:00.000000000Z",
                request.getValue().expressionAttributeValues().get(":to").s());
        assertEquals("ENGAGEMENT",
                request.getValue().expressionAttributeValues().get(":entityType").s());
    }

    @Test
    void engagementQueueContinuesWithBoundCursorAndRejectsCursorForAnotherStatus() {
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

        Page<EngagementQueueEntry> firstPage = repository.findQueue(
                SubmissionStatus.PENDING_REVIEW,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                1,
                null
        );
        Page<EngagementQueueEntry> secondPage = repository.findQueue(
                SubmissionStatus.PENDING_REVIEW,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                1,
                firstPage.nextCursor()
        );

        assertEquals("first", firstPage.items().getFirst().engagementId());
        assertNotNull(firstPage.nextCursor());
        assertEquals("second", secondPage.items().getFirst().engagementId());
        assertNull(secondPage.nextCursor());
        ArgumentCaptor<QueryRequest> requests = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDbClient, org.mockito.Mockito.times(2)).query(requests.capture());
        assertEquals(lastKey, requests.getAllValues().get(1).exclusiveStartKey());

        Map<String, AttributeValue> wrongStatusCursor = Map.of(
                "PK", string("ENTITY#first"),
                "SK", string("TYPE#ENGAGEMENT#2026-01-01T00:00:00Z"),
                "GSI1PK", string("STATUS#APPROVED"),
                "GSI1SK", string("CREATED_AT#2026-01-01T00:00:00Z#first")
        );
        assertThrows(IllegalArgumentException.class, () -> repository.findQueue(
                SubmissionStatus.PENDING_REVIEW,
                null,
                null,
                1,
                DynamoDbPageCursor.encode(wrongStatusCursor)
        ));
        verify(dynamoDbClient, org.mockito.Mockito.times(2)).query(any(QueryRequest.class));
    }

    @Test
    void reviewUsesConditionalAtomicUpdateAndWritesMinimalAuditEvent() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(List.of(engagementItem("target")))
                .build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, mock(KmsClient.class), "Saved2ServeRecords", "alias/test-email-key"
        );
        Instant reviewedAt = Instant.parse("2026-10-26T12:00:00Z");

        assertEquals(EngagementRepository.ReviewResult.UPDATED, repository.review(
                "target", SubmissionStatus.APPROVED, "leader-subject", reviewedAt
        ));

        ArgumentCaptor<TransactWriteItemsRequest> request =
                ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDbClient).transactWriteItems(request.capture());
        var actions = request.getValue().transactItems();
        assertEquals(2, actions.size());
        var update = actions.getFirst().update();
        assertEquals("#status = :pending AND #entityType = :entityType", update.conditionExpression());
        assertTrue(update.updateExpression().contains("#statusKey = :newStatusKey"));
        assertEquals("APPROVED", update.expressionAttributeValues().get(":newStatus").s());
        assertEquals("STATUS#APPROVED", update.expressionAttributeValues().get(":newStatusKey").s());
        var audit = actions.get(1).put().item();
        assertEquals("ENGAGEMENT_REVIEW", audit.get("entityType").s());
        assertEquals("PENDING_REVIEW", audit.get("previousStatus").s());
        assertEquals("APPROVED", audit.get("status").s());
        assertEquals("leader-subject", audit.get("reviewerId").s());
        assertEquals(reviewedAt.toString(), audit.get("reviewedAt").s());
        assertFalse(audit.containsKey("email"));
        assertFalse(audit.containsKey("name"));
        assertFalse(audit.containsKey("message"));
    }

    @Test
    void reviewOfMissingEngagementDoesNotWriteAnAuditEvent() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(List.of())
                .build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, mock(KmsClient.class), "Saved2ServeRecords", "alias/test-email-key"
        );

        assertEquals(EngagementRepository.ReviewResult.NOT_FOUND, repository.review(
                "missing", SubmissionStatus.REJECTED, "leader-subject", Instant.now()
        ));

        verify(dynamoDbClient, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void reviewReturnsConflictWhenConditionalTransactionDetectsConcurrentReview() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(List.of(engagementItem("target")))
                .build());
        doThrow(TransactionCanceledException.builder()
                .message("Condition did not match.")
                .cancellationReasons(List.of(
                        CancellationReason.builder().code("ConditionalCheckFailed").build(),
                        CancellationReason.builder().code("None").build()
                ))
                .build())
                .when(dynamoDbClient).transactWriteItems(any(TransactWriteItemsRequest.class));
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, mock(KmsClient.class), "Saved2ServeRecords", "alias/test-email-key"
        );

        assertEquals(EngagementRepository.ReviewResult.INVALID_TRANSITION, repository.review(
                "target", SubmissionStatus.REJECTED, "leader-subject", Instant.now()
        ));
    }

    @Test
    void findRecipientReturnsOnlyConsentStateWithoutDecryptingWhenOptedOut() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(List.of(interestItem("opted-out")))
                .build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        FollowUpRecipient recipient = repository.findRecipient("opted-out", FollowUpRecordType.INTEREST);

        assertFalse(recipient.consented());
        assertEquals("", recipient.email());
        verify(kmsClient, never()).decrypt(any(DecryptRequest.class));
    }

    @Test
    void findRecipientDecryptsPrivateFieldsOnlyForCurrentlyConsentedRecord() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        Map<String, AttributeValue> optedIn = new java.util.HashMap<>(interestItem("opted-in"));
        optedIn.put("followUpConsent", AttributeValue.builder().bool(true).build());
        optedIn.put("followUpConsentedAt", string(CONSENTED_AT.toString()));
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(List.of(optedIn))
                .build());
        when(kmsClient.decrypt(any(DecryptRequest.class))).thenReturn(DecryptResponse.builder()
                .plaintext(SdkBytes.fromUtf8String("alex@example.com"))
                .build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        FollowUpRecipient recipient = repository.findRecipient("opted-in", FollowUpRecordType.INTEREST);

        assertTrue(recipient.consented());
        assertEquals("alex@example.com", recipient.email());
        assertEquals("opted-in", recipient.name());
        verify(kmsClient).decrypt(any(DecryptRequest.class));
    }

    @Test
    void withdrawalConditionallyRecordsTimestampWithoutDecryptingPrivateData() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        Map<String, AttributeValue> optedIn = new java.util.HashMap<>(interestItem("opted-in"));
        optedIn.put("followUpConsent", AttributeValue.builder().bool(true).build());
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(List.of(optedIn))
                .build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        assertTrue(repository.withdrawConsent("opted-in", FollowUpRecordType.INTEREST));

        ArgumentCaptor<UpdateItemRequest> update = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDbClient).updateItem(update.capture());
        assertEquals("#consent = :consented AND #entityType = :entityType",
                update.getValue().conditionExpression());
        assertEquals(false, update.getValue().expressionAttributeValues().get(":withdrawn").bool());
        assertEquals("INTEREST", update.getValue().expressionAttributeValues().get(":entityType").s());
        assertTrue(update.getValue().expressionAttributeValues().containsKey(":withdrawnAt"));
        verify(kmsClient, never()).decrypt(any(DecryptRequest.class));
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
                        "A Person", "person@example.com", "10001", "", true, "landing-page", CONSENTED_AT
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
                        "A Person", "person@example.com", "10001", "", true, "landing-page", CONSENTED_AT
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
                "A Person", "person@example.com", "10001", "", true, "landing-page", CONSENTED_AT
        ));

        ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDbClient).putItem(request.capture());
        assertEquals("Saved2ServeRecords", request.getValue().tableName());
        assertEquals("EMAIL_HASH#" + EmailHasher.sha256("person@example.com"),
                request.getValue().item().get("GSI3PK").s());
        assertEquals(CONSENTED_AT.toString(), request.getValue().item().get("consentedAt").s());
        assertFalse(request.getValue().item().containsKey("email"));
        assertEquals("encrypted", request.getValue().item().get("emailCiphertext").b().asUtf8String());
    }

    @Test
    void idempotencyFingerprintLookupUsesStronglyConsistentRead() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of("requestFingerprint", string("request-fingerprint")))
                .build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, mock(KmsClient.class), "Saved2ServeRecords", "alias/test-email-key"
        );

        assertEquals("request-fingerprint",
                repository.findRegistrationFingerprintByIdempotencyKey("key-hash"));

        ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamoDbClient).getItem(request.capture());
        assertEquals("Saved2ServeRecords", request.getValue().tableName());
        assertTrue(request.getValue().consistentRead());
        assertEquals("IDEMPOTENCY#key-hash", request.getValue().key().get("PK").s());
        assertEquals("REGISTRATION", request.getValue().key().get("SK").s());
    }

    @Test
    void idempotentSaveUsesConditionalWriteAndStoresOnlyHashesForRetryIdentity() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(kmsClient.encrypt(any(EncryptRequest.class))).thenReturn(EncryptResponse.builder()
                .ciphertextBlob(SdkBytes.fromUtf8String("encrypted"))
                .build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        assertEquals(InterestRepository.IdempotentSaveResult.CREATED, repository.saveIdempotently(
                new Interest("Alex Smith", "alex@example.com", "10001", "", true, "landing-page", CONSENTED_AT),
                "key-hash",
                "request-fingerprint"
        ));

        ArgumentCaptor<TransactWriteItemsRequest> request =
                ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDbClient).transactWriteItems(request.capture());
        assertEquals(2, request.getValue().transactItems().size());
        var registration = request.getValue().transactItems().getFirst().put();
        var idempotency = request.getValue().transactItems().get(1).put();
        assertEquals("attribute_not_exists(#pk)", registration.conditionExpression());
        assertEquals("attribute_not_exists(#pk)", idempotency.conditionExpression());
        assertEquals("ENTITY#" + idempotency.item().get("recordId").s(), registration.item().get("PK").s());
        assertEquals("INTEREST", registration.item().get("entityType").s());
        assertEquals("request-fingerprint", idempotency.item().get("requestFingerprint").s());
        assertEquals("key-hash", idempotency.item().get("idempotencyKeyHash").s());
        assertEquals(5, idempotency.item().size());
        assertFalse(idempotency.item().containsKey("email"));
        assertFalse(idempotency.item().containsKey("GSI3PK"));
    }

    @Test
    void conditionalIdempotentWriteRecognizesMatchingRetry() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(kmsClient.encrypt(any(EncryptRequest.class))).thenReturn(EncryptResponse.builder()
                .ciphertextBlob(SdkBytes.fromUtf8String("encrypted"))
                .build());
        doThrow(TransactionCanceledException.builder().message("Already exists").build())
                .when(dynamoDbClient).transactWriteItems(any(TransactWriteItemsRequest.class));
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of("requestFingerprint", string("same-fingerprint")))
                .build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        assertEquals(InterestRepository.IdempotentSaveResult.REPLAYED, repository.saveIdempotently(
                new Interest("Alex Smith", "alex@example.com", "10001", "", true, "landing-page", CONSENTED_AT),
                "key-hash",
                "same-fingerprint"
        ));
    }

    @Test
    void conditionalIdempotentWriteRejectsChangedPayloadForExistingKey() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        KmsClient kmsClient = mock(KmsClient.class);
        when(kmsClient.encrypt(any(EncryptRequest.class))).thenReturn(EncryptResponse.builder()
                .ciphertextBlob(SdkBytes.fromUtf8String("encrypted"))
                .build());
        doThrow(TransactionCanceledException.builder().message("Already exists").build())
                .when(dynamoDbClient).transactWriteItems(any(TransactWriteItemsRequest.class));
        when(dynamoDbClient.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder()
                .item(Map.of("requestFingerprint", string("original-fingerprint")))
                .build());
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, "Saved2ServeRecords", "alias/test-email-key"
        );

        assertEquals(InterestRepository.IdempotentSaveResult.IDEMPOTENCY_KEY_CONFLICT,
                repository.saveIdempotently(
                        new Interest("Alex Smith", "alex@example.com", "10001", "", true, "landing-page", CONSENTED_AT),
                        "key-hash",
                        "changed-fingerprint"
                ));
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
                Map.entry("consentedAt", string(CONSENTED_AT.toString())),
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
