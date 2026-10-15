package com.saved2serve.domain;

import java.time.Instant;
import java.util.Objects;

public record DevotionLink(
        String devotionId,
        String title,
        String url,
        DevotionLinkStatus status,
        String sourceReference,
        Instant submittedAt,
        Instant verifiedAt,
        String reviewedBy,
        String reviewNote
) {
    public DevotionLink {
        devotionId = requireNonBlank(devotionId, "devotionId");
        title = requireNonBlank(title, "title");
        url = requireNonBlank(url, "url");
        status = Objects.requireNonNull(status, "status must not be null");
        sourceReference = normalize(sourceReference);
        submittedAt = Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        reviewedBy = normalize(reviewedBy);
        reviewNote = normalize(reviewNote);
        if (status == DevotionLinkStatus.APPROVED
                && (verifiedAt == null || sourceReference.isBlank() || reviewedBy.isBlank())) {
            throw new IllegalArgumentException("Approved devotion links require verification metadata.");
        }
    }

    public String platform() {
        return "YOUTUBE";
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank.");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
