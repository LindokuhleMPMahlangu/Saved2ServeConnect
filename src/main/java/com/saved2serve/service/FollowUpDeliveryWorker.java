package com.saved2serve.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saved2serve.domain.FollowUpRecordType;
import com.saved2serve.repository.FollowUpRecipient;
import com.saved2serve.repository.FollowUpRepository;

import java.util.Objects;

public final class FollowUpDeliveryWorker {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final FollowUpRepository followUpRepository;
    private final FollowUpEmailSender emailSender;
    private final FollowUpUnsubscribeToken unsubscribeToken;
    private final String unsubscribeBaseUrl;

    public FollowUpDeliveryWorker(
            FollowUpRepository followUpRepository,
            FollowUpEmailSender emailSender,
            FollowUpUnsubscribeToken unsubscribeToken,
            String unsubscribeBaseUrl
    ) {
        this.followUpRepository = Objects.requireNonNull(
                followUpRepository, "followUpRepository must not be null"
        );
        this.emailSender = Objects.requireNonNull(emailSender, "emailSender must not be null");
        this.unsubscribeToken = Objects.requireNonNull(unsubscribeToken, "unsubscribeToken must not be null");
        if (unsubscribeBaseUrl == null || unsubscribeBaseUrl.isBlank()) {
            throw new IllegalArgumentException("unsubscribeBaseUrl must not be blank.");
        }
        this.unsubscribeBaseUrl = unsubscribeBaseUrl.trim();
    }

    public void process(String messageBody) {
        QueueMessage message;
        try {
            message = OBJECT_MAPPER.readValue(messageBody, QueueMessage.class);
        } catch (JsonProcessingException invalidMessage) {
            throw new IllegalArgumentException("Follow-up queue message is invalid.", invalidMessage);
        }
        if (message.recordId() == null || message.recordId().isBlank() || message.recordType() == null) {
            throw new IllegalArgumentException("Follow-up queue message is invalid.");
        }

        FollowUpRecipient recipient = followUpRepository.findRecipient(message.recordId(), message.recordType());
        if (recipient == null || !recipient.consented()) {
            return;
        }
        String unsubscribeUrl = SesFollowUpEmailSender.unsubscribeUrl(
                unsubscribeBaseUrl, recipient, unsubscribeToken
        );
        emailSender.send(recipient.name(), recipient.email(), unsubscribeUrl);
    }

    private record QueueMessage(String recordId, FollowUpRecordType recordType) {
    }
}
