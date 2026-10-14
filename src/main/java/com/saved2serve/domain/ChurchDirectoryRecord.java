package com.saved2serve.domain;

import java.time.Instant;
import java.util.Objects;

public record ChurchDirectoryRecord(
        String churchId,
        String displayName,
        String publicWebsite,
        String areaCode,
        ChurchDirectoryStatus status,
        Instant verifiedAt,
        String sourceReference,
        String publicAddress
) {
    public ChurchDirectoryRecord(
            String churchId,
            String displayName,
            String publicWebsite,
            String areaCode,
            ChurchDirectoryStatus status,
            Instant verifiedAt,
            String sourceReference
    ) {
        this(churchId, displayName, publicWebsite, areaCode, status, verifiedAt, sourceReference, "");
    }

    public ChurchDirectoryRecord {
        churchId = requireNonBlank(churchId, "churchId");
        displayName = requireNonBlank(displayName, "displayName");
        publicWebsite = Objects.requireNonNullElse(publicWebsite, "").trim();
        areaCode = requireNonBlank(areaCode, "areaCode");
        status = Objects.requireNonNull(status, "status must not be null");
        sourceReference = Objects.requireNonNullElse(sourceReference, "").trim();
        publicAddress = Objects.requireNonNullElse(publicAddress, "").trim();
        if (status == ChurchDirectoryStatus.APPROVED
                && (verifiedAt == null || sourceReference.isBlank())) {
            throw new IllegalArgumentException("Approved churches require verification metadata.");
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank.");
        }
        return value.trim();
    }
}
