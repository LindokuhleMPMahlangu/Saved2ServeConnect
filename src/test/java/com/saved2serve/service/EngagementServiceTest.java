package com.saved2serve.service;

import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.EngagementRequest;
import com.saved2serve.domain.EngagementType;
import com.saved2serve.domain.SubmissionStatus;
import com.saved2serve.repository.EngagementRepository;
import com.saved2serve.validation.Saved2ServeValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class EngagementServiceTest {

    @Test
    void validSubmissionPersistsPendingEngagement() {
        EngagementRepository repository = mock(EngagementRepository.class);
        EngagementService service = new EngagementService(repository);

        Saved2ServeValidator.ValidationResult result = service.submit(request(
                "Alex Smith", "alex@example.com", "1800", "5551234567", true,
                EngagementType.HELP_REQUEST, "Please contact me."
        ));

        assertTrue(result.valid());
        assertEquals("accepted", result.message());
        ArgumentCaptor<Engagement> saved = ArgumentCaptor.forClass(Engagement.class);
        verify(repository).save(saved.capture());
        assertEquals(new Engagement(
                "Alex Smith",
                "alex@example.com",
                "1800",
                "5551234567",
                true,
                EngagementType.HELP_REQUEST,
                "Please contact me.",
                SubmissionStatus.PENDING_REVIEW
        ), saved.getValue());
    }

    @Test
    void invalidEmailIsRejectedWithoutPersistence() {
        EngagementRepository repository = mock(EngagementRepository.class);
        EngagementService service = new EngagementService(repository);

        Saved2ServeValidator.ValidationResult result = service.submit(request(
                "Alex Smith", "not-an-email", "1800", "", true,
                EngagementType.SUGGESTION, "Please add a local directory."
        ));

        assertFalse(result.valid());
        assertEquals("Email is invalid.", result.message());
        verify(repository, never()).save(any());
    }

    @Test
    void persistenceFailuresAreWrappedWithSafeMessageAndCause() {
        EngagementRepository repository = mock(EngagementRepository.class);
        RuntimeException persistenceFailure = new IllegalStateException("failure for alex@example.com");
        doThrow(persistenceFailure).when(repository).save(any());
        EngagementService service = new EngagementService(repository);

        EngagementService.EngagementPersistenceException failure = assertThrows(
                EngagementService.EngagementPersistenceException.class,
                () -> service.submit(request(
                        "Alex Smith", "alex@example.com", "1800", "", true,
                        EngagementType.TESTIMONY, "A short private testimony."
                ))
        );

        assertEquals("Engagement could not be submitted. Please try again.", failure.getMessage());
        assertSame(persistenceFailure, failure.getCause());
        assertFalse(failure.getMessage().contains("alex@example.com"));
    }

    private static EngagementRequest request(
            String name,
            String email,
            String areaCode,
            String phone,
            boolean consent,
            EngagementType type,
            String message
    ) {
        return new EngagementRequest(name, email, areaCode, phone, consent, type, message);
    }
}
