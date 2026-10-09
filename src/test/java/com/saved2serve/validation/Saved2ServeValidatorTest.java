package com.saved2serve.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Saved2ServeValidatorTest {

    @Test
    void validRegistrationIsAccepted() {
        Saved2ServeValidator.ValidationResult result = Saved2ServeValidator.validateRegistration(
                "Alex Smith",
                "alex@example.com",
                "10001",
                true,
                null,
                null,
                null
        );

        assertTrue(result.valid());
        assertEquals("accepted", result.message());
    }

    @Test
    void registrationRequiresConsent() {
        Saved2ServeValidator.ValidationResult result = Saved2ServeValidator.validateRegistration(
                "Alex Smith",
                "alex@example.com",
                "10001",
                false,
                null,
                null,
                null
        );

        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("consent"));
    }

    @Test
    void fullAddressIsRejected() {
        Saved2ServeValidator.ValidationResult result = Saved2ServeValidator.validateRegistration(
                "Alex Smith",
                "alex@example.com",
                "10001",
                true,
                "123 Example Street, Springfield",
                null,
                null
        );

        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("full residential address"));
    }

    @Test
    void preciseCoordinatesAreRejected() {
        Saved2ServeValidator.ValidationResult result = Saved2ServeValidator.validateRegistration(
                "Alex Smith",
                "alex@example.com",
                "10001",
                true,
                null,
                "40.7128",
                null
        );

        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("precise location"));
    }

    @Test
    void approvedHttpsDevotionLinksAreAccepted() {
        Saved2ServeValidator.ValidationResult result = Saved2ServeValidator.validateDevotionLink(
                "https://www.youtube.com/watch?v=abcd1234"
        );

        assertTrue(result.valid());
        assertEquals("accepted", result.message());
    }

    @Test
    void httpDevotionLinksAreRejected() {
        Saved2ServeValidator.ValidationResult result = Saved2ServeValidator.validateDevotionLink(
                "http://youtube.com/watch?v=abcd1234"
        );

        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("https"));
    }

    @Test
    void unapprovedDevotionPlatformsAreRejected() {
        Saved2ServeValidator.ValidationResult result = Saved2ServeValidator.validateDevotionLink(
                "https://example.com/devotion"
        );

        assertFalse(result.valid());
        assertTrue(result.message().toLowerCase().contains("approved platform"));
    }
}
