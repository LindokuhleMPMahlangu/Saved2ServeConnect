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
        SubmissionStatus status
) {
    public Engagement {
        name = normalize(name);
        email = normalize(email);
        areaCode = normalize(areaCode);
        phone = normalize(phone);
        message = normalize(message);

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
        status = Objects.requireNonNullElse(status, SubmissionStatus.PENDING_REVIEW);
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
