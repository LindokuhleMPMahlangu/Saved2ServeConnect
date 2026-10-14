package com.saved2serve.domain;

import java.util.Objects;

public record ChurchDirectoryEntry(
        String churchId,
        String displayName,
        String publicWebsite
) {
    public ChurchDirectoryEntry {
        churchId = Objects.requireNonNull(churchId, "churchId must not be null");
        displayName = Objects.requireNonNull(displayName, "displayName must not be null");
        publicWebsite = Objects.requireNonNullElse(publicWebsite, "");
    }
}
