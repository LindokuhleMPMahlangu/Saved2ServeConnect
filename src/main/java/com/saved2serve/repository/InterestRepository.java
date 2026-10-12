package com.saved2serve.repository;

import com.saved2serve.domain.Interest;

import java.util.List;

public interface InterestRepository {
    enum IdempotentSaveResult {
        CREATED,
        REPLAYED,
        IDEMPOTENCY_KEY_CONFLICT
    }

    void save(Interest interest);

    List<Interest> findByAreaCode(String areaCode);

    boolean existsByEmailHash(String emailHash);

    String findRegistrationFingerprintByIdempotencyKey(String idempotencyKeyHash);

    IdempotentSaveResult saveIdempotently(
            Interest interest,
            String idempotencyKeyHash,
            String requestFingerprint
    );
}
