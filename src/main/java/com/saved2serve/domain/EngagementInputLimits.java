package com.saved2serve.domain;

public final class EngagementInputLimits {
    public static final int NAME_MAX_LENGTH = 100;
    public static final int EMAIL_MAX_LENGTH = 254;
    public static final int AREA_CODE_MAX_LENGTH = 16;
    public static final int PHONE_MAX_LENGTH = 32;
    public static final int MESSAGE_MAX_LENGTH = 2000;

    private EngagementInputLimits() {
    }

    static String requireWithinLimit(String value, int maxLength, String fieldName) {
        if (value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(fieldName + " must be at most " + maxLength + " characters.");
        }
        return value;
    }
}
