package com.saved2serve.domain;

import java.time.Instant;
import java.util.Objects;

public record Interest(
        String name,
        String email,
        String areaCode,
        String phone,
        boolean consent,
        String source,
        Instant consentedAt,
        String recordId,
        boolean followUpConsent,
        Instant followUpConsentedAt
) {
    public Interest(
            String name,
            String email,
            String areaCode,
            String phone,
            boolean consent,
            String source,
            Instant consentedAt
    ) {
        this(name, email, areaCode, phone, consent, source, consentedAt,
                java.util.UUID.randomUUID().toString(), false, null);
    }

    public Interest {
        name = normalize(name);
        email = normalize(email);
        areaCode = normalize(areaCode);
        phone = normalize(phone);
        source = normalize(source);
        recordId = normalize(recordId);
        consentedAt = Objects.requireNonNull(consentedAt, "consentedAt must not be null");

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
            throw new IllegalArgumentException("Consent is required before an interest is accepted.");
        }
        if (recordId.isBlank()) {
            throw new IllegalArgumentException("recordId is required.");
        }
        if (followUpConsent != (followUpConsentedAt != null)) {
            throw new IllegalArgumentException("Follow-up consent timestamp must match the consent choice.");
        }
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
