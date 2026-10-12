package com.saved2serve.service;

import com.saved2serve.domain.Interest;
import com.saved2serve.domain.InterestRequest;
import com.saved2serve.repository.InterestRepository;
import com.saved2serve.validation.Saved2ServeValidator;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

public final class RegistrationService {
    private static final String DUPLICATE_REGISTRATION_MESSAGE = "Registration could not be completed.";

    private final InterestRepository interestRepository;
    private final Function<String, String> sha256Hasher;
    private final Clock clock;

    public RegistrationService(
            InterestRepository interestRepository,
            Function<String, String> sha256Hasher,
            Clock clock
    ) {
        this.interestRepository = Objects.requireNonNull(
                interestRepository, "interestRepository must not be null"
        );
        this.sha256Hasher = Objects.requireNonNull(sha256Hasher, "sha256Hasher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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

        String idempotencyKeyHash = sha256Hasher.apply(request.idempotencyKey());
        String requestFingerprint = sha256Hasher.apply(fingerprint(request));
        String existingFingerprint;
        try {
            existingFingerprint = interestRepository.findRegistrationFingerprintByIdempotencyKey(
                    idempotencyKeyHash
            );
        } catch (RuntimeException failure) {
            throw new RegistrationPersistenceException(failure);
        }
        if (existingFingerprint != null) {
            return registrationResult(existingFingerprint.equals(requestFingerprint));
        }

        String emailHash = sha256Hasher.apply(request.email().toLowerCase(Locale.ROOT));
        boolean emailExists;
        try {
            emailExists = interestRepository.existsByEmailHash(emailHash);
        } catch (RuntimeException failure) {
            throw new RegistrationPersistenceException(failure);
        }
        if (emailExists) {
            return new Saved2ServeValidator.ValidationResult(false, DUPLICATE_REGISTRATION_MESSAGE);
        }

        Interest interest = new Interest(
                request.name(),
                request.email(),
                request.areaCode(),
                request.phone(),
                request.consent(),
                request.source(),
                clock.instant()
        );
        InterestRepository.IdempotentSaveResult saveResult;
        try {
            saveResult = interestRepository.saveIdempotently(
                    interest,
                    idempotencyKeyHash,
                    requestFingerprint
            );
        } catch (RuntimeException failure) {
            throw new RegistrationPersistenceException(failure);
        }
        return switch (saveResult) {
            case CREATED, REPLAYED -> registrationResult(true);
            case IDEMPOTENCY_KEY_CONFLICT -> registrationResult(false);
        };
    }

    private static Saved2ServeValidator.ValidationResult registrationResult(boolean accepted) {
        return accepted
                ? new Saved2ServeValidator.ValidationResult(true, "accepted")
                : new Saved2ServeValidator.ValidationResult(false, DUPLICATE_REGISTRATION_MESSAGE);
    }

    private static String fingerprint(InterestRequest request) {
        return field(request.idempotencyKey())
                + field(request.name())
                + field(request.email().toLowerCase(Locale.ROOT))
                + field(request.areaCode())
                + field(request.phone())
                + field(Boolean.toString(request.consent()))
                + field(request.source());
    }

    private static String field(String value) {
        return value.length() + ":" + value;
    }

    public static final class RegistrationPersistenceException extends RuntimeException {
        private static final String SAFE_MESSAGE = "Registration could not be completed. Please try again.";

        private RegistrationPersistenceException(RuntimeException cause) {
            super(SAFE_MESSAGE, cause);
        }
    }
}
