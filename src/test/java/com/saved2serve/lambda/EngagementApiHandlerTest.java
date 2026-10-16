package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.saved2serve.domain.Engagement;
import com.saved2serve.repository.EngagementRepository;
import com.saved2serve.service.EngagementService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EngagementApiHandlerTest {
    private static final String BODY = """
            {"name":"Alex Smith","email":"alex@example.com","areaCode":"1800",
             "phone":"5551234567","consent":true,"type":"HELP_REQUEST",
             "message":"Please contact me about local support."}
            """;

    @Test
    void acceptsSubmissionPersistsPendingAndDoesNotEchoPersonalData() {
        EngagementRepository repository = mock(EngagementRepository.class);
        EngagementApiHandler handler = handler(repository);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(BODY, Map.of(
                "content-type", "application/json"
        )), null);

        assertEquals(201, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().get("cache-control"));
        assertTrue(response.getBody().contains("\"accepted\""));
        assertFalse(response.getBody().contains("Alex Smith"));
        assertFalse(response.getBody().contains("alex@example.com"));
        ArgumentCaptor<Engagement> saved = ArgumentCaptor.forClass(Engagement.class);
        verify(repository).save(saved.capture());
        assertEquals("PENDING_REVIEW", saved.getValue().status().name());
    }

    @Test
    void supportsBase64PayloadAndRejectsUnknownOrInvalidFields() {
        EngagementRepository repository = mock(EngagementRepository.class);
        EngagementApiHandler handler = handler(repository);
        APIGatewayV2HTTPEvent base64 = event(Base64.getEncoder().encodeToString(BODY.getBytes()), Map.of(
                "content-type", "application/json"
        ));
        base64.setIsBase64Encoded(true);
        String unknownField = BODY.stripTrailing().substring(0, BODY.stripTrailing().length() - 1)
                + ",\"fullAddress\":\"private\"}";
        String invalidType = BODY.replace("\"type\":\"HELP_REQUEST\"", "\"type\":\"OTHER\"");

        assertEquals(201, handler.handleRequest(base64, null).getStatusCode());
        assertEquals(400, handler.handleRequest(event(unknownField, Map.of(
                "content-type", "application/json"
        )), null).getStatusCode());
        assertEquals(400, handler.handleRequest(event(invalidType, Map.of(
                "content-type", "application/json"
        )), null).getStatusCode());
        verify(repository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void rejectsMissingConsentMalformedJsonAndOversizedPayload() {
        EngagementApiHandler handler = handler(mock(EngagementRepository.class));
        String noConsent = BODY.replace("\"consent\":true", "\"consent\":false");

        assertEquals(400, handler.handleRequest(event(noConsent, Map.of(
                "content-type", "application/json"
        )), null).getStatusCode());
        assertEquals(400, handler.handleRequest(event("{invalid", Map.of(
                "content-type", "application/json"
        )), null).getStatusCode());
        assertEquals(413, handler.handleRequest(event("x".repeat(16 * 1024 + 1), Map.of(
                "content-type", "application/json"
        )), null).getStatusCode());
    }

    @Test
    void rejectsUnsupportedContentTypeMethodAndPathWithoutPersistence() {
        EngagementRepository repository = mock(EngagementRepository.class);
        EngagementApiHandler handler = handler(repository);

        assertEquals(415, handler.handleRequest(event(BODY, Map.of(
                "content-type", "text/plain"
        )), null).getStatusCode());
        assertEquals(405, handler.handleRequest(event("GET", "/v1/engagements", BODY, Map.of(
                "content-type", "application/json"
        )), null).getStatusCode());
        assertEquals(404, handler.handleRequest(event("POST", "/v1/other", BODY, Map.of(
                "content-type", "application/json"
        )), null).getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test
    void returnsGenericUnavailableResponseForPersistenceFailure() {
        EngagementRepository repository = mock(EngagementRepository.class);
        List<String> logMessages = new ArrayList<>();
        Logger logger = Logger.getLogger(EngagementApiHandler.class.getName());
        Handler logCapture = new Handler() {
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
        doThrow(DynamoDbException.builder()
                .message("failure for alex@example.com")
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("InternalServerError")
                        .build())
                .build())
                .when(repository).save(any());

        logger.addHandler(logCapture);
        try {
            APIGatewayV2HTTPResponse response = handler(repository).handleRequest(
                    event(BODY, Map.of("content-type", "application/json")), null
            );

            assertEquals(503, response.getStatusCode());
            assertFalse(response.getBody().contains("alex@example.com"));
            assertFalse(response.getBody().contains("InternalServerError"));
            assertTrue(logMessages.stream().noneMatch(message ->
                    message.contains("alex@example.com") || message.contains("Please contact me")));
        } finally {
            logger.removeHandler(logCapture);
        }
    }

    private static EngagementApiHandler handler(EngagementRepository repository) {
        return new EngagementApiHandler(new EngagementService(repository));
    }

    private static APIGatewayV2HTTPEvent event(String body, Map<String, String> headers) {
        return event("POST", "/v1/engagements", body, headers);
    }

    private static APIGatewayV2HTTPEvent event(
            String method,
            String path,
            String body,
            Map<String, String> headers
    ) {
        return APIGatewayV2HTTPEvent.builder()
                .withRawPath(path)
                .withHeaders(headers)
                .withBody(body)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder().withMethod(method).build())
                        .build())
                .build();
    }
}
