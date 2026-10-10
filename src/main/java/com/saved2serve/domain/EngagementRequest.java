package com.saved2serve.domain;

import java.util.Objects;
import java.util.Set;

public record EngagementRequest(
        String name,
        String email,
        String areaCode,
        String phone,
        boolean consent,
        EngagementType type,
        String message
) {
    public static final Set<String> ALLOWED_FIELDS = Set.of(
            "name",
            "email",
            "areaCode",
            "phone",
            "consent",
            "type",
            "message"
    );

    public EngagementRequest {
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
            throw new IllegalArgumentException("Consent is required before an engagement request is accepted.");
        }
        if (type == null) {
            throw new IllegalArgumentException("Engagement type is required.");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("Engagement message is required.");
        }
    }

    public static boolean allowsField(String fieldName) {
        return ALLOWED_FIELDS.contains(fieldName);
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
