package com.saved2serve.validation;

import java.net.URI;
import java.util.Set;

public final class Saved2ServeValidator {

    private static final Set<String> APPROVED_DEVOTION_HOSTS = Set.of(
            "youtube.com",
            "www.youtube.com",
            "youtu.be",
            "www.youtu.be"
    );

    private Saved2ServeValidator() {
    }

    public record ValidationResult(boolean valid, String message) {
    }

    public static ValidationResult validateRegistration(
            String name,
            String email,
            String areaCode,
            boolean consent,
            String fullAddress,
            String latitude,
            String longitude
    ) {
        if (name == null || name.isBlank()) {
            return new ValidationResult(false, "Name is required.");
        }

        if (email == null || email.isBlank() || !email.contains("@") || !email.contains(".")) {
            return new ValidationResult(false, "Email is invalid.");
        }

        if (areaCode == null || areaCode.isBlank()) {
            return new ValidationResult(false, "Area code is required.");
        }

        if (!consent) {
            return new ValidationResult(false, "Consent is required before registration is accepted.");
        }

        if (fullAddress != null && !fullAddress.isBlank()) {
            return new ValidationResult(false, "Full residential addresses are not accepted.");
        }

        if (isLatitudeOrLongitudeProvided(latitude) || isLatitudeOrLongitudeProvided(longitude)) {
            return new ValidationResult(false, "Precise location coordinates are not accepted.");
        }

        return new ValidationResult(true, "accepted");
    }

    public static ValidationResult validateDevotionLink(String url) {
        if (url == null || url.isBlank()) {
            return new ValidationResult(false, "Devotion URL is required.");
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return new ValidationResult(false, "Devotion URL is invalid.");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return new ValidationResult(false, "Devotion links must use HTTPS.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return new ValidationResult(false, "Approved devotion platform host is required.");
        }

        String normalizedHost = host.toLowerCase();
        if (!APPROVED_DEVOTION_HOSTS.contains(normalizedHost)) {
            return new ValidationResult(false, "Approved platform required for devotion links.");
        }

        return new ValidationResult(true, "accepted");
    }

    private static boolean isLatitudeOrLongitudeProvided(String value) {
        return value != null && !value.isBlank() && value.matches("[-+]?\\d{1,3}(\\.\\d+)?");
    }
}
