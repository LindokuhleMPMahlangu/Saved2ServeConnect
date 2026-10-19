package com.saved2serve.service;

import com.saved2serve.domain.FollowUpRecordType;
import com.saved2serve.repository.FollowUpRecipient;
import com.saved2serve.repository.FollowUpRepository;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowUpDeliveryWorkerTest {
    private static final String RECORD_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String BASE_URL = "https://example.org/unsubscribe";
    private static final String MESSAGE =
            "{\"recordId\":\"" + RECORD_ID + "\",\"recordType\":\"ENGAGEMENT\"}";

    @Test
    void issuesSignedUnsubscribeUrlAndSendsOnlyAfterCurrentConsentCheck() {
        FollowUpRepository repository = mock(FollowUpRepository.class);
        FollowUpEmailSender sender = mock(FollowUpEmailSender.class);
        when(repository.findRecipient(RECORD_ID, FollowUpRecordType.ENGAGEMENT)).thenReturn(
                new FollowUpRecipient(
                        RECORD_ID, FollowUpRecordType.ENGAGEMENT, "Alex Smith", "alex@example.com", true
                )
        );
        FollowUpDeliveryWorker worker = worker(repository, sender);

        worker.process(MESSAGE);

        ArgumentCaptor<String> unsubscribeUrl = ArgumentCaptor.forClass(String.class);
        verify(sender).send(org.mockito.ArgumentMatchers.eq("Alex Smith"),
                org.mockito.ArgumentMatchers.eq("alex@example.com"), unsubscribeUrl.capture());
        assertTrue(unsubscribeUrl.getValue().startsWith(BASE_URL + "?"));
        assertTrue(unsubscribeUrl.getValue().contains("recordId=" + RECORD_ID));
        assertTrue(unsubscribeUrl.getValue().contains("recordType=ENGAGEMENT"));
        String token = java.net.URLDecoder.decode(
                unsubscribeUrl.getValue().substring(unsubscribeUrl.getValue().indexOf("token=") + 6),
                java.nio.charset.StandardCharsets.UTF_8
        );
        assertTrue(new FollowUpUnsubscribeToken(SECRET)
                .verifies(token, RECORD_ID, FollowUpRecordType.ENGAGEMENT));
        verify(repository).findRecipient(RECORD_ID, FollowUpRecordType.ENGAGEMENT);
    }

    @Test
    void suppressesMessagesWhenConsentWasWithdrawnOrRecordIsMissing() {
        FollowUpRepository repository = mock(FollowUpRepository.class);
        FollowUpEmailSender sender = mock(FollowUpEmailSender.class);
        when(repository.findRecipient(RECORD_ID, FollowUpRecordType.ENGAGEMENT))
                .thenReturn(new FollowUpRecipient(RECORD_ID, FollowUpRecordType.ENGAGEMENT, "", "", false))
                .thenReturn(null);
        FollowUpDeliveryWorker worker = worker(repository, sender);

        worker.process(MESSAGE);
        worker.process(MESSAGE);

        verify(sender, never()).send(any(), any(), any());
    }

    @Test
    void letsSesFailuresEscapeSoSqsRetriesWithoutLoggingMessageData() {
        FollowUpRepository repository = mock(FollowUpRepository.class);
        FollowUpEmailSender sender = mock(FollowUpEmailSender.class);
        when(repository.findRecipient(RECORD_ID, FollowUpRecordType.ENGAGEMENT)).thenReturn(
                new FollowUpRecipient(
                        RECORD_ID, FollowUpRecordType.ENGAGEMENT, "Alex Smith", "alex@example.com", true
                )
        );
        RuntimeException sesFailure = new IllegalStateException("provider unavailable");
        org.mockito.Mockito.doThrow(sesFailure).when(sender).send(any(), any(), any());

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> worker(repository, sender).process(MESSAGE));

        assertEquals(sesFailure, thrown);
    }

    @Test
    void sqsMessageContainsOnlyOpaqueRecordIdentity() {
        software.amazon.awssdk.services.sqs.SqsClient sqsClient =
                mock(software.amazon.awssdk.services.sqs.SqsClient.class);
        SqsFollowUpQueue queue = new SqsFollowUpQueue(sqsClient, "https://sqs.example/queue");
        ArgumentCaptor<software.amazon.awssdk.services.sqs.model.SendMessageRequest> request =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.sqs.model.SendMessageRequest.class);

        queue.enqueue(RECORD_ID, FollowUpRecordType.ENGAGEMENT);

        verify(sqsClient).sendMessage(request.capture());
        String body = request.getValue().messageBody();
        assertTrue(body.contains(RECORD_ID));
        assertTrue(body.contains("ENGAGEMENT"));
        assertFalse(body.contains("alex@example.com"));
        assertFalse(body.contains("Alex Smith"));
        assertFalse(body.contains("message"));
    }

    @Test
    void unsubscribeTokenIsBoundToRecordIdAndType() {
        FollowUpUnsubscribeToken signer = new FollowUpUnsubscribeToken(SECRET);
        String token = signer.issue(RECORD_ID, FollowUpRecordType.INTEREST);

        assertTrue(signer.verifies(token, RECORD_ID, FollowUpRecordType.INTEREST));
        assertFalse(signer.verifies(token, RECORD_ID, FollowUpRecordType.ENGAGEMENT));
        assertFalse(signer.verifies(token, "22222222-2222-4222-8222-222222222222",
                FollowUpRecordType.INTEREST));
        String tampered = (token.charAt(0) == 'A' ? "B" : "A") + token.substring(1);
        assertFalse(signer.verifies(tampered,
                RECORD_ID, FollowUpRecordType.INTEREST));
        assertThrows(IllegalArgumentException.class, () -> new FollowUpUnsubscribeToken("short"));
    }

    @Test
    void sesEmailUsesGenericContentAndContainsNoSubmittedMessage() {
        SesV2Client sesClient = mock(SesV2Client.class);
        SesFollowUpEmailSender sender = new SesFollowUpEmailSender(sesClient, "noreply@example.org");

        sender.send("Alex Smith", "alex@example.com", "https://example.org/unsubscribe");

        ArgumentCaptor<SendEmailRequest> request = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(request.capture());
        assertEquals("alex@example.com", request.getValue().destination().toAddresses().getFirst());
        String content = request.getValue().content().simple().body().text().data();
        assertTrue(content.contains("https://example.org/unsubscribe"));
        assertFalse(content.contains("Please contact me"));
    }

    private static FollowUpDeliveryWorker worker(
            FollowUpRepository repository,
            FollowUpEmailSender sender
    ) {
        return new FollowUpDeliveryWorker(
                repository,
                sender,
                new FollowUpUnsubscribeToken(SECRET),
                BASE_URL
        );
    }
}
