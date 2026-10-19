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
        String message,
        boolean followUpConsent
) {
    public static final Set<String> ALLOWED_FIELDS = Set.of(
            "name",
            "email",
            "areaCode",
            "phone",
            "consent",
            "type",
            "message",
            "followUpConsent"
    );

    public EngagementRequest(
            String name,
            String email,
            String areaCode,
            String phone,
            boolean consent,
            EngagementType type,
            String message
    ) {
        this(name, email, areaCode, phone, consent, type, message, false);
    }

    public EngagementRequest {
        name = normalize(name);
        email = normalize(email);
        areaCode = normalize(areaCode);
        phone = normalize(phone);
        message = normalize(message);

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
