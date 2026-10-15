package com.saved2serve.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevotionLinkTest {
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-24T10:15:30Z");
    private static final Instant VERIFIED_AT = Instant.parse("2026-10-25T10:00:00Z");

    @Test
    void approvedLinkRequiresSourceAndReviewMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new DevotionLink(
                "devotion-1",
                "Sunday message",
                "https://youtu.be/video123",
                DevotionLinkStatus.APPROVED,
                "",
                SUBMITTED_AT,
                VERIFIED_AT,
                "",
                ""
        ));
    }

    @Test
    void platformIsDerivedForApprovedYoutubeUrl() {
        DevotionLink link = new DevotionLink(
                "devotion-1",
                "Sunday message",
                "https://youtu.be/video123",
                DevotionLinkStatus.APPROVED,
                "official-channel-confirmation",
                SUBMITTED_AT,
                VERIFIED_AT,
                "leader-1",
                null
        );

        assertEquals("YOUTUBE", link.platform());
        assertEquals("", link.reviewNote());
    }
}
