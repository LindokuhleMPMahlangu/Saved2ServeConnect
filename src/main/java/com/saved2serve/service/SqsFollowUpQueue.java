package com.saved2serve.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saved2serve.domain.FollowUpRecordType;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Objects;

public final class SqsFollowUpQueue implements FollowUpQueue {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SqsClient sqsClient;
    private final String queueUrl;

    public SqsFollowUpQueue(SqsClient sqsClient, String queueUrl) {
        this.sqsClient = Objects.requireNonNull(sqsClient, "sqsClient must not be null");
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalArgumentException("queueUrl must not be blank.");
        }
        this.queueUrl = queueUrl.trim();
    }

    @Override
    public void enqueue(String recordId, FollowUpRecordType recordType) {
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("recordId must not be blank.");
        }
        Objects.requireNonNull(recordType, "recordType must not be null");
        String body;
        try {
            body = OBJECT_MAPPER.writeValueAsString(new QueueMessage(recordId, recordType));
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Follow-up notification could not be queued.", serializationFailure);
        }
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .build());
    }

    private record QueueMessage(String recordId, FollowUpRecordType recordType) {
    }
}
