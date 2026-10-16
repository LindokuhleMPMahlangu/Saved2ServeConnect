package com.saved2serve.domain;

import java.util.Objects;

public record HelpRequest(String message) {
    public HelpRequest {
        message = normalize(message);
        if (message.isBlank()) {
            throw new IllegalArgumentException("Help request message is required.");
        }
        message = EngagementInputLimits.requireWithinLimit(
                message,
                EngagementInputLimits.MESSAGE_MAX_LENGTH,
                "Help request message"
        );
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
