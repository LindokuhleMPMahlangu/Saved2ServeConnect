package com.saved2serve.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
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
                "landing-page"
        );

        assertEquals("Alex Smith", interest.name());
        assertEquals("alex@example.com", interest.email());
        assertEquals("10001", interest.areaCode());
        assertEquals("5551234567", interest.phone());
        assertTrue(interest.consent());
        assertEquals("landing-page", interest.source());

        Set<String> names = Arrays.stream(Interest.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertTrue(names.containsAll(Set.of("name", "email", "areaCode", "phone", "consent", "source")));
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
        assertFalse(containsField(Engagement.class, "fullAddress"));
        assertFalse(containsField(Engagement.class, "latitude"));
        assertFalse(containsField(Engagement.class, "longitude"));
    }

    @Test
    void interestRequestUsesExplicitAllowlistOnly() {
        assertEquals(Set.of("idempotencyKey", "name", "email", "areaCode", "phone", "consent", "source"),
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
    }

    @Test
    void engagementRequestUsesExplicitAllowlistOnly() {
        assertEquals(Set.of("name", "email", "areaCode", "phone", "consent", "type", "message"), EngagementRequest.ALLOWED_FIELDS);
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
