package com.saved2serve.repository;

import com.saved2serve.domain.FollowUpRecordType;

import java.util.Objects;

public record FollowUpRecipient(
        String recordId,
        FollowUpRecordType recordType,
        String name,
        String email,
        boolean consented
) {
    public FollowUpRecipient {
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("recordId must not be blank.");
        }
        recordType = Objects.requireNonNull(recordType, "recordType must not be null");
        name = Objects.requireNonNullElse(name, "").trim();
        email = Objects.requireNonNullElse(email, "").trim();
    }
}
