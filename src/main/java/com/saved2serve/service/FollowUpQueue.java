package com.saved2serve.service;

import com.saved2serve.domain.FollowUpRecordType;

public interface FollowUpQueue {
    void enqueue(String recordId, FollowUpRecordType recordType);
}
