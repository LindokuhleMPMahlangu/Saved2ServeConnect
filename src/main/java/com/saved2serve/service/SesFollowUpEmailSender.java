package com.saved2serve.service;

import com.saved2serve.domain.FollowUpRecordType;
import com.saved2serve.repository.FollowUpRecipient;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class SesFollowUpEmailSender implements FollowUpEmailSender {
    private static final String SUBJECT = "Following up on your Save2Serve request";
    private final SesV2Client sesClient;
    private final String fromAddress;

    public SesFollowUpEmailSender(SesV2Client sesClient, String fromAddress) {
        this.sesClient = Objects.requireNonNull(sesClient, "sesClient must not be null");
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalArgumentException("fromAddress must not be blank.");
        }
        this.fromAddress = fromAddress.trim();
    }

    @Override
    public void send(String recipientName, String recipientEmail, String unsubscribeUrl) {
        String name = recipientName == null || recipientName.isBlank() ? "there" : recipientName.trim();
        String body = "Hello " + name + ",\n\n"
                + "We are following up on your Save2Serve request. "
                + "If you need help, please reply to this email.\n\n"
                + "To stop follow-up emails, visit: " + unsubscribeUrl + "\n";
        sesClient.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(fromAddress)
                .destination(Destination.builder().toAddresses(recipientEmail).build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data(SUBJECT).charset("UTF-8").build())
                                .body(Body.builder()
                                        .text(Content.builder().data(body).charset("UTF-8").build())
                                        .build())
                                .build())
                        .build())
                .build());
    }

    public static String unsubscribeUrl(
            String baseUrl,
            FollowUpRecipient recipient,
            FollowUpUnsubscribeToken token
    ) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("unsubscribe base URL must not be blank.");
        }
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator
                + "recordId=" + encode(recipient.recordId())
                + "&recordType=" + encode(recipient.recordType().name())
                + "&token=" + encode(token.issue(recipient.recordId(), recipient.recordType()));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
