package com.saved2serve.service;

import com.saved2serve.domain.Interest;
import com.saved2serve.domain.InterestRequest;
import com.saved2serve.repository.InterestRepository;
import com.saved2serve.validation.Saved2ServeValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    private static final String NORMALIZED_EMAIL_HASH =
            "6db61e6dcbcf2390e4a46af426f26a133a3bee45021422fc7ae86e9136f14110";

    @Test
    void validRegistrationChecksEmailHashAndSavesMinimalInterest() {
        InterestRepository repository = mock(InterestRepository.class);
        when(repository.existsByEmailHash(NORMALIZED_EMAIL_HASH)).thenReturn(false);
        RegistrationService service = new RegistrationService(repository, emailHasher("Alex@Example.com"));
        InterestRequest request = new InterestRequest(
                " Alex Smith ",
                " Alex@Example.com ",
                " 10001 ",
                " 5551234567 ",
                true,
                " landing-page "
        );

        Saved2ServeValidator.ValidationResult result = service.register(request);

        assertTrue(result.valid());
        assertEquals("accepted", result.message());
        verify(repository).existsByEmailHash(NORMALIZED_EMAIL_HASH);
        ArgumentCaptor<Interest> interest = ArgumentCaptor.forClass(Interest.class);
        verify(repository).save(interest.capture());
        assertEquals(new Interest(
                "Alex Smith", "Alex@Example.com", "10001", "5551234567", true, "landing-page"
        ), interest.getValue());
    }

    @Test
    void duplicateRegistrationIsRejectedWithoutSaving() {
        InterestRepository repository = mock(InterestRepository.class);
        when(repository.existsByEmailHash(NORMALIZED_EMAIL_HASH)).thenReturn(true);
        RegistrationService service = new RegistrationService(repository, emailHasher("alex@example.com"));
        InterestRequest request = new InterestRequest(
                "Alex Smith", "alex@example.com", "10001", "", true, "landing-page"
        );

        Saved2ServeValidator.ValidationResult result = service.register(request);

        assertFalse(result.valid());
        assertEquals("Registration could not be completed.", result.message());
        verify(repository).existsByEmailHash(NORMALIZED_EMAIL_HASH);
        verify(repository, never()).save(any(Interest.class));
    }

    @Test
    void invalidEmailIsRejectedBeforeRepositoryAccess() {
        InterestRepository repository = mock(InterestRepository.class);
        Function<String, String> emailHasher = email -> {
            throw new AssertionError("Invalid registration must not hash an email.");
        };
        RegistrationService service = new RegistrationService(repository, emailHasher);
        InterestRequest request = new InterestRequest(
                "Alex Smith", "not-an-email", "10001", "", true, "landing-page"
        );

        Saved2ServeValidator.ValidationResult result = service.register(request);

        assertFalse(result.valid());
        assertEquals("Email is invalid.", result.message());
        verifyNoInteractions(repository);
    }

    @Test
    void registrationRequestRequiresConsentBeforeServiceCanAcceptIt() {
        assertThrows(IllegalArgumentException.class, () -> new InterestRequest(
                "Alex Smith", "alex@example.com", "10001", "", false, "landing-page"
        ));
    }

    private static Function<String, String> emailHasher(String expectedEmail) {
        return email -> {
            assertEquals(expectedEmail, email);
            return NORMALIZED_EMAIL_HASH;
        };
    }
}
