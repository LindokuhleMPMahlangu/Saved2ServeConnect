package com.saved2serve.repository;

import com.saved2serve.domain.FollowUpRecordType;

public interface FollowUpRepository {
    FollowUpRecipient findRecipient(String recordId, FollowUpRecordType recordType);

    boolean withdrawConsent(String recordId, FollowUpRecordType recordType);
}
