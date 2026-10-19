package com.saved2serve.service;

public interface FollowUpEmailSender {
    void send(String recipientName, String recipientEmail, String unsubscribeUrl);
}
