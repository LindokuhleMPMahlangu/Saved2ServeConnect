package com.saved2serve.persistence.dynamodb;

import com.saved2serve.domain.DevotionLink;
import com.saved2serve.domain.DevotionLinkStatus;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.kms.KmsClient;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class DevotionLinkRecordMapperTest {
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-24T10:15:30Z");
    private static final Instant VERIFIED_AT = Instant.parse("2026-10-25T10:00:00Z");

    @Test
    void approvedLinkMapsToDynamoDbAndBackWithInternalReviewMetadata() {
        DynamoDbRecordMapper mapper = new DynamoDbRecordMapper(mock(KmsClient.class), "alias/test-key");
        DevotionLink link = approvedLink("https://www.youtube.com/watch?v=video123");

        Map<String, AttributeValue> item = mapper.toItem(link);
        DevotionLink restored = mapper.toDevotionLink(item);

        assertEquals(link, restored);
        assertEquals("DEVOTION#devotion-1", item.get("PK").s());
        assertEquals("LINK", item.get("SK").s());
        assertEquals("DEVOTION_LINK", item.get("entityType").s());
        assertEquals("YOUTUBE", item.get("platform").s());
        assertEquals("APPROVED", item.get("status").s());
        assertEquals("STATUS#APPROVED", item.get("GSI1PK").s());
        assertEquals("official-channel-confirmation", item.get("sourceReference").s());
        assertEquals("leader-1", item.get("reviewedBy").s());
    }

    @Test
    void mapperRejectsPendingLinksAndUnapprovedHosts() {
        DynamoDbRecordMapper mapper = new DynamoDbRecordMapper(mock(KmsClient.class), "alias/test-key");
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
        DevotionLink unapprovedHost = approvedLink("https://example.com/video123");

        assertThrows(IllegalArgumentException.class, () -> mapper.toItem(pending));
        assertThrows(IllegalArgumentException.class, () -> mapper.toItem(unapprovedHost));
    }

    private static DevotionLink approvedLink(String url) {
        return new DevotionLink(
                "devotion-1",
                "Sunday message",
                url,
                DevotionLinkStatus.APPROVED,
                "official-channel-confirmation",
                SUBMITTED_AT,
                VERIFIED_AT,
                "leader-1",
                "Reviewed for publication."
        );
    }
}
