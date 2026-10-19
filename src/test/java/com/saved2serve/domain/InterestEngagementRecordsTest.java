package com.saved2serve.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class InterestEngagementRecordsTest {

    @Test
    void interestRecordUsesMinimalPrivacyAwareFields() {
        Interest interest = new Interest(
                "Alex Smith",
                "alex@example.com",
                "10001",
                "5551234567",
                true,
                "landing-page",
                Instant.parse("2026-10-24T10:00:00Z")
        );

        assertEquals("Alex Smith", interest.name());
        assertEquals("alex@example.com", interest.email());
        assertEquals("10001", interest.areaCode());
        assertEquals("5551234567", interest.phone());
        assertTrue(interest.consent());
        assertEquals("landing-page", interest.source());
        assertEquals(Instant.parse("2026-10-24T10:00:00Z"), interest.consentedAt());
        assertFalse(interest.followUpConsent());
        assertNull(interest.followUpConsentedAt());

        Set<String> names = Arrays.stream(Interest.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertTrue(names.containsAll(Set.of(
                "name", "email", "areaCode", "phone", "consent", "source", "consentedAt"
        )));
        assertFalse(containsField(Interest.class, "fullAddress"));
        assertFalse(containsField(Interest.class, "latitude"));
        assertFalse(containsField(Interest.class, "longitude"));
    }

    @Test
    void engagementRecordDefaultsToPendingReviewAndKeepsMinimalData() {
        Engagement engagement = new Engagement(
                "Jordan Lee",
                "jordan@example.com",
                "20001",
                "5559876543",
                true,
                EngagementType.HELP_REQUEST,
                "I need prayer support for a new volunteer team.",
                SubmissionStatus.PENDING_REVIEW
        );

        assertEquals("Jordan Lee", engagement.name());
        assertEquals("jordan@example.com", engagement.email());
        assertEquals("20001", engagement.areaCode());
        assertEquals(EngagementType.HELP_REQUEST, engagement.type());
        assertEquals(SubmissionStatus.PENDING_REVIEW, engagement.status());
        assertFalse(engagement.followUpConsent());
        assertNull(engagement.followUpConsentedAt());
        assertFalse(containsField(Engagement.class, "fullAddress"));
        assertFalse(containsField(Engagement.class, "latitude"));
        assertFalse(containsField(Engagement.class, "longitude"));
    }

    @Test
    void interestRequestUsesExplicitAllowlistOnly() {
        assertEquals(Set.of("idempotencyKey", "name", "email", "areaCode", "phone", "consent", "source",
                        "followUpConsent"),
                InterestRequest.ALLOWED_FIELDS);
        assertFalse(InterestRequest.ALLOWED_FIELDS.contains("fullAddress"));
        assertFalse(InterestRequest.ALLOWED_FIELDS.contains("latitude"));
        assertFalse(InterestRequest.ALLOWED_FIELDS.contains("longitude"));
        assertFalse(InterestRequest.ALLOWED_FIELDS.contains("status"));

        InterestRequest request = new InterestRequest(
                "registration-attempt-123",
                "Morgan Lee",
                "morgan@example.com",
                "10001",
                "5551112233",
                true,
                "qr-code"
        );

        assertEquals("registration-attempt-123", request.idempotencyKey());
        assertEquals("Morgan Lee", request.name());
        assertEquals("10001", request.areaCode());
        assertFalse(request.followUpConsent());
    }

    @Test
    void engagementRequestUsesExplicitAllowlistOnly() {
        assertEquals(Set.of("name", "email", "areaCode", "phone", "consent", "type", "message",
                        "followUpConsent"), EngagementRequest.ALLOWED_FIELDS);
        assertFalse(EngagementRequest.ALLOWED_FIELDS.contains("fullAddress"));
        assertFalse(EngagementRequest.ALLOWED_FIELDS.contains("latitude"));
        assertFalse(EngagementRequest.ALLOWED_FIELDS.contains("longitude"));
        assertFalse(EngagementRequest.ALLOWED_FIELDS.contains("status"));

        EngagementRequest request = new EngagementRequest(
                "Jordan Lee",
                "jordan@example.com",
                "20001",
                "5559876543",
                true,
                EngagementType.HELP_REQUEST,
                "I need prayer support for a new volunteer team."
        );

        assertEquals(EngagementType.HELP_REQUEST, request.type());
        assertEquals("I need prayer support for a new volunteer team.", request.message());
        assertFalse(request.followUpConsent());
    }

    @Test
    void helpRequestAndFeedbackNormalizeAndEnforceMessageLimit() {
        String withinLimit = "x".repeat(2000);
        assertEquals(withinLimit, new HelpRequest(" " + withinLimit + " ").message());
        assertEquals(withinLimit, new Feedback(withinLimit).message());

        assertThrows(IllegalArgumentException.class, () -> new HelpRequest("x".repeat(2001)));
        assertThrows(IllegalArgumentException.class, () -> new Feedback("x".repeat(2001)));
        assertThrows(IllegalArgumentException.class, () -> new HelpRequest("  "));
        assertThrows(IllegalArgumentException.class, () -> new Feedback(null));
    }

    @Test
    void engagementRequestRejectsOversizedInputsAndAcceptsUnicodeByCodePoint() {
        assertThrows(IllegalArgumentException.class, () -> new EngagementRequest(
                "Alex", "alex@example.com", "x".repeat(17), "", true,
                EngagementType.HELP_REQUEST, "Please contact me."
        ));
        assertThrows(IllegalArgumentException.class, () -> new EngagementRequest(
                "Alex", "alex@example.com", "10001", "x".repeat(33), true,
                EngagementType.HELP_REQUEST, "Please contact me."
        ));
        assertThrows(IllegalArgumentException.class, () -> new EngagementRequest(
                "x".repeat(101), "alex@example.com", "10001", "", true,
                EngagementType.HELP_REQUEST, "Please contact me."
        ));
        assertThrows(IllegalArgumentException.class, () -> new EngagementRequest(
                "Alex", "x".repeat(255), "10001", "", true,
                EngagementType.HELP_REQUEST, "Please contact me."
        ));
        assertThrows(IllegalArgumentException.class, () -> new EngagementRequest(
                "Alex", "alex@example.com", "10001", "", true,
                EngagementType.HELP_REQUEST, "x".repeat(2001)
        ));

        EngagementRequest atFieldLimits = new EngagementRequest(
                "n".repeat(100), "e".repeat(254), "a".repeat(16), "p".repeat(32), true,
                EngagementType.HELP_REQUEST, "m"
        );
        assertEquals(100, atFieldLimits.name().codePointCount(0, atFieldLimits.name().length()));
        assertEquals(254, atFieldLimits.email().codePointCount(0, atFieldLimits.email().length()));
        assertEquals(16, atFieldLimits.areaCode().codePointCount(0, atFieldLimits.areaCode().length()));
        assertEquals(32, atFieldLimits.phone().codePointCount(0, atFieldLimits.phone().length()));

        String supplementaryCharacter = "\uD83D\uDE4F";
        EngagementRequest request = new EngagementRequest(
                "Alex", "alex@example.com", "10001", "", true,
                EngagementType.HELP_REQUEST, supplementaryCharacter.repeat(2000)
        );
        assertEquals(2000, request.message().codePointCount(0, request.message().length()));
    }

    @Test
    void engagementRecordRejectsMessagesAboveLimit() {
        assertThrows(IllegalArgumentException.class, () -> new Engagement(
                "Jordan Lee",
                "jordan@example.com",
                "20001",
                "",
                true,
                EngagementType.SUGGESTION,
                "x".repeat(2001),
                SubmissionStatus.PENDING_REVIEW
        ));
    }

    private static boolean containsField(Class<?> type, String fieldName) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().equals(fieldName)) {
                return true;
            }
        }
        return false;
    }
}
