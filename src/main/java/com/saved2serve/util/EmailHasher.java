package com.saved2serve.util;

import java.util.Locale;
import java.util.Objects;

public final class EmailHasher {
    private EmailHasher() {
    }

    public static String sha256(String email) {
        String canonicalEmail = Objects.requireNonNull(email, "email must not be null")
                .trim()
                .toLowerCase(Locale.ROOT);
        return Hashing.sha256(canonicalEmail);
    }
}
