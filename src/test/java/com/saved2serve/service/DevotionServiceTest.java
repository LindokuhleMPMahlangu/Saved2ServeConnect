package com.saved2serve.service;

import com.saved2serve.domain.DevotionLink;
import com.saved2serve.domain.DevotionLinkEntry;
import com.saved2serve.domain.DevotionLinkStatus;
import com.saved2serve.repository.DevotionLinkRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DevotionServiceTest {
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-24T10:15:30Z");
    private static final Instant VERIFIED_AT = Instant.parse("2026-10-25T10:00:00Z");

    @Test
    void returnsOnlyApprovedValidYoutubeLinksAsPublicFields() {
        DevotionLinkRepository repository = new DevotionLinkRepository() {
            @Override
            public void saveApproved(DevotionLink devotionLink) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DevotionLink> findPublished() {
                return List.of(
                        approved("devotion-1", "https://youtu.be/video123"),
                        new DevotionLink(
                                "devotion-2", "Pending message", "https://youtu.be/video456",
                                DevotionLinkStatus.PENDING_REVIEW, "", SUBMITTED_AT, null, "", ""
                        ),
                        approved("devotion-3", "https://example.com/video789")
                );
            }
        };

        List<DevotionLinkEntry> results = new DevotionService(repository).findPublished();

        assertEquals(1, results.size());
        assertEquals("devotion-1", results.getFirst().devotionId());
        assertEquals("https://youtu.be/video123", results.getFirst().url());
    }

    private static DevotionLink approved(String id, String url) {
        return new DevotionLink(
                id,
                "Weekly message",
                url,
                DevotionLinkStatus.APPROVED,
                "verified-public-channel",
                SUBMITTED_AT,
                VERIFIED_AT,
                "authorized-reviewer",
                "internal review note"
        );
    }
}
