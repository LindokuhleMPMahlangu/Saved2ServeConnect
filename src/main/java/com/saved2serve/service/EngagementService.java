package com.saved2serve.service;

import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.EngagementRequest;
import com.saved2serve.domain.SubmissionStatus;
import com.saved2serve.repository.EngagementRepository;
import com.saved2serve.validation.Saved2ServeValidator;

import java.util.Objects;

public final class EngagementService {
    private static final String SAFE_FAILURE_MESSAGE = "Engagement could not be submitted. Please try again.";

    private final EngagementRepository engagementRepository;

    public EngagementService(EngagementRepository engagementRepository) {
        this.engagementRepository = Objects.requireNonNull(
                engagementRepository, "engagementRepository must not be null"
        );
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

    public static final class EngagementPersistenceException extends RuntimeException {
        private EngagementPersistenceException(RuntimeException cause) {
            super(SAFE_FAILURE_MESSAGE, cause);
        }
    }
}
