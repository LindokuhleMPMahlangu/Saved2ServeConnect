package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.saved2serve.domain.FollowUpRecordType;
import com.saved2serve.repository.FollowUpRecipient;
import com.saved2serve.repository.FollowUpRepository;
import com.saved2serve.service.FollowUpDeliveryWorker;
import com.saved2serve.service.FollowUpEmailSender;
import com.saved2serve.service.FollowUpUnsubscribeToken;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FollowUpDeliveryHandlerTest {
    private static final String FIRST_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SECOND_ID = "22222222-2222-4222-8222-222222222222";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void returnsOnlyFailedMessageIdsForSqsRetry() {
        FollowUpRepository repository = mock(FollowUpRepository.class);
        FollowUpEmailSender sender = mock(FollowUpEmailSender.class);
        when(repository.findRecipient(FIRST_ID, FollowUpRecordType.INTEREST)).thenReturn(
                new FollowUpRecipient(FIRST_ID, FollowUpRecordType.INTEREST, "A", "a@example.org", true)
        );
        when(repository.findRecipient(SECOND_ID, FollowUpRecordType.INTEREST)).thenReturn(
                new FollowUpRecipient(SECOND_ID, FollowUpRecordType.INTEREST, "B", "b@example.org", true)
        );
        doAnswer(invocation -> {
            if ("b@example.org".equals(invocation.getArgument(1))) {
                throw new IllegalStateException("SES unavailable");
            }
            return null;
        }).when(sender).send(anyString(), anyString(), anyString());
        FollowUpDeliveryWorker worker = new FollowUpDeliveryWorker(
                repository,
                sender,
                new FollowUpUnsubscribeToken(SECRET),
                "https://example.org/unsubscribe"
        );
        FollowUpDeliveryHandler handler = new FollowUpDeliveryHandler(worker);
        Logger logger = Logger.getLogger(FollowUpDeliveryHandler.class.getName());
        List<String> logMessages = new java.util.ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logMessages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(
                message("message-1", FIRST_ID),
                message("message-2", SECOND_ID)
        ));

        SQSBatchResponse response;
        try {
            response = handler.handleRequest(event, null);
        } finally {
            logger.removeHandler(capture);
        }

        assertEquals(List.of("message-2"), response.getBatchItemFailures().stream()
                .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .toList());
        String loggedText = String.join(" ", logMessages);
        assertFalse(loggedText.contains("a@example.org"));
        assertFalse(loggedText.contains("b@example.org"));
        assertFalse(loggedText.contains(FIRST_ID));
        assertFalse(loggedText.contains(SECOND_ID));
    }

    private static SQSEvent.SQSMessage message(String messageId, String recordId) {
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId(messageId);
        message.setBody("{\"recordId\":\"" + recordId + "\",\"recordType\":\"INTEREST\"}");
        return message;
    }
}
