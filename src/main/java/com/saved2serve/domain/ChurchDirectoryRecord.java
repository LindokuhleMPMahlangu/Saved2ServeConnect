package com.saved2serve.domain;

import java.util.Objects;

public record ChurchDirectoryRecord(
        String churchId,
        String displayName,
        String publicWebsite,
        String areaCode,
        ChurchDirectoryStatus status
) {
    public ChurchDirectoryRecord {
        churchId = requireNonBlank(churchId, "churchId");
        displayName = requireNonBlank(displayName, "displayName");
        publicWebsite = Objects.requireNonNullElse(publicWebsite, "").trim();
        areaCode = requireNonBlank(areaCode, "areaCode");
        status = Objects.requireNonNull(status, "status must not be null");
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank.");
        }
        return value.trim();
    }
}
