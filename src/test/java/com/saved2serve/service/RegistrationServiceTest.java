package com.saved2serve.service;

import com.saved2serve.domain.Interest;
import com.saved2serve.domain.InterestRequest;
import com.saved2serve.repository.InterestRepository;
import com.saved2serve.util.Hashing;
import com.saved2serve.validation.Saved2ServeValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Locale;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RegistrationServiceTest {
    private static final String IDEMPOTENCY_KEY = "signup-attempt-123";
    private static final String IDEMPOTENCY_KEY_HASH = Hashing.sha256(IDEMPOTENCY_KEY);
    private static final String NORMALIZED_EMAIL_HASH = Hashing.sha256("alex@example.com");

    @Test
    void validRegistrationChecksEmailHashAndSavesMinimalInterest() {
        InterestRepository repository = mock(InterestRepository.class);
        InterestRequest request = request(
                IDEMPOTENCY_KEY,
                " Alex Smith ",
                " Alex@Example.com ",
                " 10001 ",
                " 5551234567 ",
                true,
                " landing-page "
        );
        String fingerprint = fingerprint(request);
        when(repository.findRegistrationFingerprintByIdempotencyKey(IDEMPOTENCY_KEY_HASH)).thenReturn(null);
        when(repository.existsByEmailHash(NORMALIZED_EMAIL_HASH)).thenReturn(false);
        when(repository.saveIdempotently(any(Interest.class), any(String.class), any(String.class)))
                .thenReturn(InterestRepository.IdempotentSaveResult.CREATED);
        RegistrationService service = new RegistrationService(repository, Hashing::sha256);

        Saved2ServeValidator.ValidationResult result = service.register(request);

        assertTrue(result.valid());
        assertEquals("accepted", result.message());
        verify(repository).findRegistrationFingerprintByIdempotencyKey(IDEMPOTENCY_KEY_HASH);
        verify(repository).existsByEmailHash(NORMALIZED_EMAIL_HASH);
        ArgumentCaptor<Interest> interest = ArgumentCaptor.forClass(Interest.class);
        verify(repository).saveIdempotently(
                interest.capture(),
                org.mockito.ArgumentMatchers.eq(IDEMPOTENCY_KEY_HASH),
                org.mockito.ArgumentMatchers.eq(fingerprint)
        );
        assertEquals(new Interest(
                "Alex Smith", "Alex@Example.com", "10001", "5551234567", true, "landing-page"
        ), interest.getValue());
    }

    @Test
    void duplicateRegistrationIsRejectedWithoutSaving() {
        InterestRepository repository = mock(InterestRepository.class);
        InterestRequest request = request(
                IDEMPOTENCY_KEY,
                "Alex Smith", "alex@example.com", "10001", "", true, "landing-page"
        );
        when(repository.findRegistrationFingerprintByIdempotencyKey(IDEMPOTENCY_KEY_HASH)).thenReturn(null);
        when(repository.existsByEmailHash(NORMALIZED_EMAIL_HASH)).thenReturn(true);
        RegistrationService service = new RegistrationService(repository, Hashing::sha256);

        Saved2ServeValidator.ValidationResult result = service.register(request);

        assertFalse(result.valid());
        assertEquals("Registration could not be completed.", result.message());
        verify(repository).existsByEmailHash(NORMALIZED_EMAIL_HASH);
        verify(repository, never()).saveIdempotently(any(Interest.class), any(String.class), any(String.class));
    }

    @Test
    void sameKeyAndNormalizedRequestReplaySuccessWithoutDuplicateLookupOrWrite() {
        InterestRepository repository = mock(InterestRepository.class);
        InterestRequest request = request(
                IDEMPOTENCY_KEY, "Alex Smith", "Alex@Example.com", "10001", "", true, "landing-page"
        );
        when(repository.findRegistrationFingerprintByIdempotencyKey(IDEMPOTENCY_KEY_HASH))
                .thenReturn(null, fingerprint(request));
        when(repository.existsByEmailHash(NORMALIZED_EMAIL_HASH)).thenReturn(false);
        when(repository.saveIdempotently(any(Interest.class), any(String.class), any(String.class)))
                .thenReturn(InterestRepository.IdempotentSaveResult.CREATED);
        RegistrationService service = new RegistrationService(repository, Hashing::sha256);

        Saved2ServeValidator.ValidationResult initial = service.register(request);
        Saved2ServeValidator.ValidationResult retry = service.register(request(
                IDEMPOTENCY_KEY, "Alex Smith", " alex@example.com ", "10001", "", true, "landing-page"
        ));

        assertTrue(initial.valid());
        assertTrue(retry.valid());
        assertEquals("accepted", retry.message());
        verify(repository, org.mockito.Mockito.times(2))
                .findRegistrationFingerprintByIdempotencyKey(IDEMPOTENCY_KEY_HASH);
        verify(repository).existsByEmailHash(NORMALIZED_EMAIL_HASH);
        verify(repository).saveIdempotently(any(Interest.class), any(String.class), any(String.class));
    }

    @Test
    void reusingKeyForDifferentRequestIsRejectedWithoutEmailLookup() {
        InterestRepository repository = mock(InterestRepository.class);
        InterestRequest original = request(
                IDEMPOTENCY_KEY, "Alex Smith", "alex@example.com", "10001", "", true, "landing-page"
        );
        InterestRequest changed = request(
                IDEMPOTENCY_KEY, "Alex Smith", "different@example.com", "10001", "", true, "landing-page"
        );
        when(repository.findRegistrationFingerprintByIdempotencyKey(IDEMPOTENCY_KEY_HASH))
                .thenReturn(fingerprint(original));
        RegistrationService service = new RegistrationService(repository, Hashing::sha256);

        Saved2ServeValidator.ValidationResult result = service.register(changed);

        assertFalse(result.valid());
        assertEquals("Registration could not be completed.", result.message());
        verify(repository, never()).existsByEmailHash(any(String.class));
        verify(repository, never()).saveIdempotently(any(Interest.class), any(String.class), any(String.class));
    }

    @Test
    void conditionalWriteReplayIsAccepted() {
        InterestRepository repository = mock(InterestRepository.class);
        InterestRequest request = request(
                IDEMPOTENCY_KEY, "Alex Smith", "alex@example.com", "10001", "", true, "landing-page"
        );
        when(repository.findRegistrationFingerprintByIdempotencyKey(IDEMPOTENCY_KEY_HASH)).thenReturn(null);
        when(repository.existsByEmailHash(NORMALIZED_EMAIL_HASH)).thenReturn(false);
        when(repository.saveIdempotently(any(Interest.class), any(String.class), any(String.class)))
                .thenReturn(InterestRepository.IdempotentSaveResult.REPLAYED);
        RegistrationService service = new RegistrationService(repository, emailHasher);

        Saved2ServeValidator.ValidationResult result = service.register(request);

        assertTrue(result.valid());
        assertEquals("accepted", result.message());
    }

    @Test
    void invalidEmailIsRejectedBeforeRepositoryAccess() {
        InterestRepository repository = mock(InterestRepository.class);
        Function<String, String> emailHasher = email -> {
            throw new AssertionError("Invalid registration must not hash request values.");
        };
        RegistrationService service = new RegistrationService(repository, emailHasher);
        InterestRequest request = request(
                IDEMPOTENCY_KEY,
                "Alex Smith", "not-an-email", "10001", "", true, "landing-page"
        );

        Saved2ServeValidator.ValidationResult result = service.register(request);

        assertFalse(result.valid());
        assertEquals("Email is invalid.", result.message());
        verifyNoInteractions(repository);
    }

    @Test
    void registrationRequestRequiresConsentBeforeServiceCanAcceptIt() {
        assertThrows(IllegalArgumentException.class, () -> request(
                IDEMPOTENCY_KEY, "Alex Smith", "alex@example.com", "10001", "", false, "landing-page"
        ));
    }

    @Test
    void idempotencyKeyIsRequiredAndLengthLimited() {
        assertThrows(IllegalArgumentException.class, () -> request(
                " ", "Alex Smith", "alex@example.com", "10001", "", true, "landing-page"
        ));
        assertThrows(IllegalArgumentException.class, () -> request(
                "k".repeat(129), "Alex Smith", "alex@example.com", "10001", "", true, "landing-page"
        ));
    }

    private static InterestRequest request(
            String idempotencyKey,
            String name,
            String email,
            String areaCode,
            String phone,
            boolean consent,
            String source
    ) {
        return new InterestRequest(idempotencyKey, name, email, areaCode, phone, consent, source);
    }

    private static String fingerprint(InterestRequest request) {
        String canonicalRequest = field(request.idempotencyKey())
                + field(request.name())
                + field(request.email().toLowerCase(Locale.ROOT))
                + field(request.areaCode())
                + field(request.phone())
                + field(Boolean.toString(request.consent()))
                + field(request.source());
        return Hashing.sha256(canonicalRequest);
    }

    private static String field(String value) {
        return value.length() + ":" + value;
    }
}
