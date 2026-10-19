package com.saved2serve.service;

import com.saved2serve.domain.FollowUpRecordType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;

public final class FollowUpUnsubscribeToken {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    public FollowUpUnsubscribeToken(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Unsubscribe signing secret must be at least 32 bytes.");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(String recordId, FollowUpRecordType recordType) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload(recordId, recordType)));
    }

    public boolean verifies(String token, String recordId, FollowUpRecordType recordType) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            byte[] supplied = Base64.getUrlDecoder().decode(token);
            return MessageDigest.isEqual(supplied, sign(payload(recordId, recordType)));
        } catch (IllegalArgumentException malformedToken) {
            return false;
        }
    }

    private static byte[] payload(String recordId, FollowUpRecordType recordType) {
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("recordId must not be blank.");
        }
        Objects.requireNonNull(recordType, "recordType must not be null");
        return (recordType.name() + ":" + recordId).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] sign(byte[] value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(value);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Unsubscribe token could not be verified.", failure);
        }
    }
}
