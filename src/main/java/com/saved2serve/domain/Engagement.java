package com.saved2serve.domain;

import java.util.Objects;

public record Engagement(
        String name,
        String email,
        String areaCode,
        String phone,
        boolean consent,
        EngagementType type,
        String message,
        SubmissionStatus status,
        String recordId,
        boolean followUpConsent,
        java.time.Instant followUpConsentedAt
) {
    public Engagement(
            String name,
            String email,
            String areaCode,
            String phone,
            boolean consent,
            EngagementType type,
            String message,
            SubmissionStatus status
    ) {
        this(name, email, areaCode, phone, consent, type, message, status,
                java.util.UUID.randomUUID().toString(), false, null);
    }

    public Engagement {
        name = normalize(name);
        email = normalize(email);
        areaCode = normalize(areaCode);
        phone = normalize(phone);
        message = normalize(message);
        recordId = normalize(recordId);

        EngagementInputLimits.requireWithinLimit(name, EngagementInputLimits.NAME_MAX_LENGTH, "Name");
        EngagementInputLimits.requireWithinLimit(email, EngagementInputLimits.EMAIL_MAX_LENGTH, "Email");
        EngagementInputLimits.requireWithinLimit(areaCode, EngagementInputLimits.AREA_CODE_MAX_LENGTH, "Area code");
        EngagementInputLimits.requireWithinLimit(phone, EngagementInputLimits.PHONE_MAX_LENGTH, "Phone");
        EngagementInputLimits.requireWithinLimit(message, EngagementInputLimits.MESSAGE_MAX_LENGTH, "Message");

        if (name.isBlank()) {
            throw new IllegalArgumentException("Name is required.");
        }
        if (email.isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }
        if (areaCode.isBlank()) {
            throw new IllegalArgumentException("Area code is required.");
        }
        if (!consent) {
            throw new IllegalArgumentException("Consent is required before an engagement is accepted.");
        }
        if (type == null) {
            throw new IllegalArgumentException("Engagement type is required.");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("Engagement message is required.");
        }
        if (recordId.isBlank()) {
            throw new IllegalArgumentException("recordId is required.");
        }
        if (followUpConsent != (followUpConsentedAt != null)) {
            throw new IllegalArgumentException("Follow-up consent timestamp must match the consent choice.");
        }
        status = Objects.requireNonNullElse(status, SubmissionStatus.PENDING_REVIEW);
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
