package com.saved2serve.service;

import com.saved2serve.domain.Interest;
import com.saved2serve.domain.InterestRequest;
import com.saved2serve.repository.InterestRepository;
import com.saved2serve.util.EmailHasher;
import com.saved2serve.validation.Saved2ServeValidator;

import java.util.Objects;

public final class RegistrationService {
    private static final String DUPLICATE_REGISTRATION_MESSAGE = "Registration could not be completed.";

    private final InterestRepository interestRepository;

    public RegistrationService(InterestRepository interestRepository) {
        this.interestRepository = Objects.requireNonNull(
                interestRepository, "interestRepository must not be null"
        );
    }

    public Saved2ServeValidator.ValidationResult register(InterestRequest request) {
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

        String emailHash = EmailHasher.sha256(request.email());
        if (interestRepository.existsByEmailHash(emailHash)) {
            return new Saved2ServeValidator.ValidationResult(false, DUPLICATE_REGISTRATION_MESSAGE);
        }

        interestRepository.save(new Interest(
                request.name(),
                request.email(),
                request.areaCode(),
                request.phone(),
                request.consent(),
                request.source()
        ));
        return new Saved2ServeValidator.ValidationResult(true, "accepted");
    }
}
