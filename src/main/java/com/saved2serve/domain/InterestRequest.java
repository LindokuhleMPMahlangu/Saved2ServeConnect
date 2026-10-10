package com.saved2serve.domain;

import java.util.Objects;
import java.util.Set;

public record InterestRequest(
        String name,
        String email,
        String areaCode,
        String phone,
        boolean consent,
        String source
) {
    public static final Set<String> ALLOWED_FIELDS = Set.of(
            "name",
            "email",
            "areaCode",
            "phone",
            "consent",
            "source"
    );

    public InterestRequest {
        name = normalize(name);
        email = normalize(email);
        areaCode = normalize(areaCode);
        phone = normalize(phone);
        source = normalize(source);

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
