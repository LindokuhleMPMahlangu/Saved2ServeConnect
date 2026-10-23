package com.saved2serve.domain;

import java.util.Objects;
import java.util.Set;

public record InterestRequest(
        String idempotencyKey,
        String name,
        String email,
        String areaCode,
        String phone,
        boolean consent,
        String source,
        boolean followUpConsent
) {
    private static final Set<String> ALLOWED_SOURCES = Set.of(
            "",
            "landing-page",
            "qr-code",
            "campaign-qr:community-outreach"
    );
    public static final Set<String> ALLOWED_FIELDS = Set.of(
            "idempotencyKey",
            "name",
            "email",
            "areaCode",
            "phone",
            "consent",
            "source",
            "followUpConsent"
    );

    public InterestRequest(
            String idempotencyKey,
            String name,
            String email,
            String areaCode,
            String phone,
            boolean consent,
            String source
    ) {
        this(idempotencyKey, name, email, areaCode, phone, consent, source, false);
    }

    public InterestRequest {
        idempotencyKey = normalize(idempotencyKey);
        name = normalize(name);
        email = normalize(email);
        areaCode = normalize(areaCode);
        phone = normalize(phone);
        source = normalize(source);

        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("A valid idempotency key is required.");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("Name is required.");
        }
        if (email.isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }
        if (areaCode.isBlank()) {
            throw new IllegalArgumentException("Area code is required.");
        }
        if (!ALLOWED_SOURCES.contains(source)) {
            throw new IllegalArgumentException("Source is invalid.");
        }
        if (!consent) {
            throw new IllegalArgumentException("Consent is required before an interest request is accepted.");
        }
    }

    public static boolean allowsField(String fieldName) {
        return ALLOWED_FIELDS.contains(fieldName);
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
