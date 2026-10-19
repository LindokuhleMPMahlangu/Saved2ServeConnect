package com.saved2serve.service;

import com.saved2serve.domain.Interest;
import com.saved2serve.domain.InterestRequest;
import com.saved2serve.domain.FollowUpRecordType;
import com.saved2serve.repository.InterestRepository;
import com.saved2serve.validation.Saved2ServeValidator;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public final class RegistrationService {
    private static final String DUPLICATE_REGISTRATION_MESSAGE = "Registration could not be completed.";

    private final InterestRepository interestRepository;
    private final Function<String, String> sha256Hasher;
    private final Clock clock;
    private final FollowUpQueue followUpQueue;

    public RegistrationService(
            InterestRepository interestRepository,
            Function<String, String> sha256Hasher,
            Clock clock
    ) {
        this(interestRepository, sha256Hasher, clock, null);
    }

    public RegistrationService(
            InterestRepository interestRepository,
            Function<String, String> sha256Hasher,
            Clock clock,
            FollowUpQueue followUpQueue
    ) {
        this.interestRepository = Objects.requireNonNull(
                interestRepository, "interestRepository must not be null"
        );
        this.sha256Hasher = Objects.requireNonNull(sha256Hasher, "sha256Hasher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.followUpQueue = followUpQueue;
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
            boolean accepted = existingFingerprint.equals(requestFingerprint);
            if (accepted) {
                enqueueFollowUpIfConsented(
                        stableRecordId(idempotencyKeyHash), request.followUpConsent()
                );
            }
            return registrationResult(accepted);
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

        Instant consentedAt = clock.instant();
        Interest interest = new Interest(
                request.name(),
                request.email(),
                request.areaCode(),
                request.phone(),
                request.consent(),
                request.source(),
                consentedAt,
                stableRecordId(idempotencyKeyHash),
                request.followUpConsent(),
                request.followUpConsent() ? consentedAt : null
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
            case CREATED, REPLAYED -> {
                enqueueFollowUpIfConsented(interest.recordId(), request.followUpConsent());
                yield registrationResult(true);
            }
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
                + field(request.source())
                + field(Boolean.toString(request.followUpConsent()));
    }

    private void enqueueFollowUpIfConsented(String recordId, boolean followUpConsent) {
        if (!followUpConsent) {
            return;
        }
        if (followUpQueue == null) {
            throw new RegistrationPersistenceException(new IllegalStateException(
                    "Follow-up queue is not configured."
            ));
        }
        try {
            followUpQueue.enqueue(recordId, FollowUpRecordType.INTEREST);
        } catch (RuntimeException failure) {
            throw new RegistrationPersistenceException(failure);
        }
    }

    private static String stableRecordId(String idempotencyKeyHash) {
        byte[] bytes = java.util.HexFormat.of().parseHex(idempotencyKeyHash.substring(0, 32));
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
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
