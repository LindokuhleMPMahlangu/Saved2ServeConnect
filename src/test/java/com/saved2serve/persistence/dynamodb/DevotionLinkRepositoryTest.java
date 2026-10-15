package com.saved2serve.persistence.dynamodb;

import com.saved2serve.domain.DevotionLink;
import com.saved2serve.domain.DevotionLinkStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.kms.KmsClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevotionLinkRepositoryTest {
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-24T10:15:30Z");
    private static final Instant VERIFIED_AT = Instant.parse("2026-10-25T10:00:00Z");

    @Test
    void savesOnlyApprovedDevotionLinks() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        DynamoDbSaved2ServeRepository repository = repository(dynamoDbClient);

        repository.saveApproved(approvedLink());

        ArgumentCaptor<PutItemRequest> request = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamoDbClient).putItem(request.capture());
        assertEquals("Saved2ServeRecords", request.getValue().tableName());
        assertEquals("DEVOTION#devotion-1", request.getValue().item().get("PK").s());
        assertEquals("STATUS#APPROVED", request.getValue().item().get("GSI1PK").s());
    }

    @Test
    void rejectsPendingOrInvalidLinksWithoutWriting() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        DynamoDbSaved2ServeRepository repository = repository(dynamoDbClient);
        DevotionLink pending = new DevotionLink(
                "devotion-1",
                "Sunday message",
                "https://youtu.be/video123",
                DevotionLinkStatus.PENDING_REVIEW,
                "",
                SUBMITTED_AT,
                null,
                "",
                ""
        );
        DevotionLink invalidUrl = new DevotionLink(
                "devotion-2",
                "Sunday message",
                "https://example.com/video123",
                DevotionLinkStatus.APPROVED,
                "official-channel-confirmation",
                SUBMITTED_AT,
                VERIFIED_AT,
                "leader-1",
                ""
        );

        assertThrows(IllegalArgumentException.class, () -> repository.saveApproved(pending));
        assertThrows(IllegalArgumentException.class, () -> repository.saveApproved(invalidUrl));
        verify(dynamoDbClient, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void retrievesApprovedLinksUsingStatusIndexAndEntityFilters() {
        DynamoDbClient dynamoDbClient = mock(DynamoDbClient.class);
        when(dynamoDbClient.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                .items(List.of(approvedItem()))
                .build());
        DynamoDbSaved2ServeRepository repository = repository(dynamoDbClient);

        List<DevotionLink> published = repository.findPublished();

        assertEquals(List.of(approvedLink()), published);
        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDbClient).query(request.capture());
        assertEquals(DynamoDbSaved2ServeRepository.STATUS_CREATED_AT_INDEX, request.getValue().indexName());
        assertEquals("#statusKey = :statusKey", request.getValue().keyConditionExpression());
        assertEquals("#entityType = :entityType AND #status = :status", request.getValue().filterExpression());
        assertEquals("STATUS#APPROVED", request.getValue().expressionAttributeValues().get(":statusKey").s());
        assertEquals("DEVOTION_LINK", request.getValue().expressionAttributeValues().get(":entityType").s());
        assertEquals("APPROVED", request.getValue().expressionAttributeValues().get(":status").s());
        assertFalse(request.getValue().scanIndexForward());
    }

    private static DynamoDbSaved2ServeRepository repository(DynamoDbClient dynamoDbClient) {
        return new DynamoDbSaved2ServeRepository(
                dynamoDbClient, mock(KmsClient.class), "Saved2ServeRecords", "alias/test-key"
        );
    }

    private static DevotionLink approvedLink() {
        return new DevotionLink(
                "devotion-1",
                "Sunday message",
                "https://youtu.be/video123",
                DevotionLinkStatus.APPROVED,
                "official-channel-confirmation",
                SUBMITTED_AT,
                VERIFIED_AT,
                "leader-1",
                "Reviewed for publication."
        );
    }

    private static Map<String, AttributeValue> approvedItem() {
        return Map.of(
                "devotionId", string("devotion-1"),
                "title", string("Sunday message"),
                "url", string("https://youtu.be/video123"),
                "platform", string("YOUTUBE"),
                "status", string("APPROVED"),
                "sourceReference", string("official-channel-confirmation"),
                "submittedAt", string(SUBMITTED_AT.toString()),
                "verifiedAt", string(VERIFIED_AT.toString()),
                "reviewedBy", string("leader-1"),
                "reviewNote", string("Reviewed for publication.")
        );
    }

    private static AttributeValue string(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
