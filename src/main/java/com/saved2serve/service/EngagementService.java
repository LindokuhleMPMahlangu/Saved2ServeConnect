package com.saved2serve.service;

import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.EngagementRequest;
import com.saved2serve.domain.SubmissionStatus;
import com.saved2serve.repository.EngagementRepository;
import com.saved2serve.repository.EngagementQueueEntry;
import com.saved2serve.repository.Page;
import com.saved2serve.validation.Saved2ServeValidator;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class EngagementService {
    private static final String SAFE_FAILURE_MESSAGE = "Engagement could not be submitted. Please try again.";

    private final EngagementRepository engagementRepository;
    private final Clock clock;

    public EngagementService(EngagementRepository engagementRepository) {
        this(engagementRepository, Clock.systemUTC());
    }

    public EngagementService(EngagementRepository engagementRepository, Clock clock) {
        this.engagementRepository = Objects.requireNonNull(
                engagementRepository, "engagementRepository must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Saved2ServeValidator.ValidationResult submit(EngagementRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        Saved2ServeValidator.ValidationResult validation = Saved2ServeValidator.validateRegistration(
                request.name(),
                request.email(),
                request.areaCode(),
                request.consent(),
                null,
                null,
                null
        );
        if (!validation.valid()) {
            return validation;
        }

        Engagement engagement = new Engagement(
                request.name(),
                request.email(),
                request.areaCode(),
                request.phone(),
                request.consent(),
                request.type(),
                request.message(),
                SubmissionStatus.PENDING_REVIEW
        );
        try {
            engagementRepository.save(engagement);
        } catch (RuntimeException failure) {
            throw new EngagementPersistenceException(failure);
        }
        return new Saved2ServeValidator.ValidationResult(true, "accepted");
    }

    public Page<EngagementQueueEntry> findQueue(
            SubmissionStatus status,
            Instant fromInclusive,
            Instant toExclusive,
            int pageSize,
            String cursor
    ) {
        Objects.requireNonNull(status, "status must not be null");
        if (fromInclusive != null && toExclusive != null && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("from must be before to.");
        }
        return engagementRepository.findQueue(status, fromInclusive, toExclusive, pageSize, cursor);
    }

    public EngagementRepository.ReviewResult review(
            String engagementId,
            SubmissionStatus newStatus,
            String reviewerId
    ) {
        if (engagementId == null || engagementId.isBlank()) {
            throw new IllegalArgumentException("engagementId must not be blank.");
        }
        if (newStatus != SubmissionStatus.APPROVED && newStatus != SubmissionStatus.REJECTED) {
            throw new IllegalArgumentException("A review status must be APPROVED or REJECTED.");
        }
        if (reviewerId == null || reviewerId.isBlank()) {
            throw new IllegalArgumentException("reviewerId must not be blank.");
        }
        return engagementRepository.review(
                engagementId, newStatus, reviewerId, Instant.now(clock)
        );
    }

    public static final class EngagementPersistenceException extends RuntimeException {
        private EngagementPersistenceException(RuntimeException cause) {
            super(SAFE_FAILURE_MESSAGE, cause);
        }
    }
}
