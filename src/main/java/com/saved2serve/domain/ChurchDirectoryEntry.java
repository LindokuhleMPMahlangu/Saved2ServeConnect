package com.saved2serve.domain;

import java.util.Objects;

public record ChurchDirectoryEntry(
        String churchId,
        String displayName,
        String publicWebsite,
        String publicAddress
) {
    public ChurchDirectoryEntry(String churchId, String displayName, String publicWebsite) {
        this(churchId, displayName, publicWebsite, "");
    }

    public ChurchDirectoryEntry {
        churchId = Objects.requireNonNull(churchId, "churchId must not be null");
        displayName = Objects.requireNonNull(displayName, "displayName must not be null");
        publicWebsite = Objects.requireNonNullElse(publicWebsite, "");
        publicAddress = Objects.requireNonNullElse(publicAddress, "");
    }
}
