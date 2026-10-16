package com.saved2serve.domain;

import java.util.Objects;

public record Feedback(String message) {
    public Feedback {
        message = normalize(message);
        if (message.isBlank()) {
            throw new IllegalArgumentException("Feedback message is required.");
        }
        message = EngagementInputLimits.requireWithinLimit(
                message,
                EngagementInputLimits.MESSAGE_MAX_LENGTH,
                "Feedback message"
        );
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
