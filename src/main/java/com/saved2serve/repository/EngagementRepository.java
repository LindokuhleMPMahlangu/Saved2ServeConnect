package com.saved2serve.repository;

import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.SubmissionStatus;

import java.util.List;
import java.time.Instant;

public interface EngagementRepository {
    void save(Engagement engagement);

    List<Engagement> findByStatus(SubmissionStatus status);

    Page<Engagement> findByStatus(SubmissionStatus status, int pageSize, String cursor);

    Page<EngagementQueueEntry> findQueue(
            SubmissionStatus status,
            Instant fromInclusive,
            Instant toExclusive,
            int pageSize,
            String cursor
    );

    ReviewResult review(
            String engagementId,
            SubmissionStatus newStatus,
            String reviewerId,
            Instant reviewedAt
    );

    List<Engagement> findByAreaCodeAndStatus(String areaCode, SubmissionStatus status);

    enum ReviewResult {
        UPDATED,
        NOT_FOUND,
        INVALID_TRANSITION
    }
}
