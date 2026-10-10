package com.saved2serve.domain;

import java.util.Objects;

public record Interest(
        String name,
        String email,
        String areaCode,
        String phone,
        boolean consent,
        String source
) {
    public Interest {
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
            throw new IllegalArgumentException("Consent is required before an interest is accepted.");
        }
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
