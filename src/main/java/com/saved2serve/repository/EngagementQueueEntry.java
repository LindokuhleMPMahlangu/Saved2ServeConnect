package com.saved2serve.repository;

import com.saved2serve.domain.Engagement;

import java.time.Instant;
import java.util.Objects;

public record EngagementQueueEntry(String engagementId, Instant createdAt, Engagement engagement) {
    public EngagementQueueEntry {
        if (engagementId == null || engagementId.isBlank()) {
            throw new IllegalArgumentException("engagementId must not be blank.");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        engagement = Objects.requireNonNull(engagement, "engagement must not be null");
    }
}
