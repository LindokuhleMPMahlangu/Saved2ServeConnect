package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.saved2serve.repository.InterestRepository;
import com.saved2serve.service.RegistrationService;
import com.saved2serve.util.Hashing;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterestApiHandlerTest {
    private static final String BODY = """
            {"idempotencyKey":"request-123","name":"Alex Smith","email":"alex@example.com",
             "areaCode":"10001","phone":"5551234567","consent":true,"source":"landing-page"}
            """;
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC
    );

    @Test
    void acceptsValidJsonAndDoesNotReturnPersonalInformation() {
        InterestRepository repository = mock(InterestRepository.class);
        when(repository.findRegistrationFingerprintByIdempotencyKey(Hashing.sha256("request-123")))
                .thenReturn(null);
        when(repository.existsByEmailHash(Hashing.sha256("alex@example.com"))).thenReturn(false);
        when(repository.saveIdempotently(any(), any(), any()))
                .thenReturn(InterestRepository.IdempotentSaveResult.CREATED);
        InterestApiHandler handler = handler(repository);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(BODY, Map.of(
                "Content-Type", "application/json; charset=utf-8"
        )), null);

        assertEquals(201, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().get("cache-control"));
        assertTrue(response.getBody().contains("\"accepted\""));
        assertFalse(response.getBody().contains("Alex Smith"));
        assertFalse(response.getBody().contains("alex@example.com"));
        assertFalse(response.getBody().contains("5551234567"));
        verify(repository).saveIdempotently(any(), any(), any());
    }

    @Test
    void rejectsIncorrectContentTypeAndMalformedJson() {
        InterestApiHandler handler = handler(mock(InterestRepository.class));

        APIGatewayV2HTTPResponse unsupported = handler.handleRequest(
                event(BODY, Map.of("content-type", "text/plain")), null
        );
        APIGatewayV2HTTPResponse malformed = handler.handleRequest(
                event("{invalid", Map.of("content-type", "application/json")), null
        );

        assertEquals(415, unsupported.getStatusCode());
        assertEquals(400, malformed.getStatusCode());
    }

    @Test
    void rejectsDuplicateFieldsAndTrailingJsonValues() {
        InterestApiHandler handler = handler(mock(InterestRepository.class));
        String duplicateField = BODY.replace("\"consent\":true", "\"consent\":true,\"consent\":false");

        APIGatewayV2HTTPResponse duplicate = handler.handleRequest(
                event(duplicateField, Map.of("content-type", "application/json")), null
        );
        APIGatewayV2HTTPResponse trailingValue = handler.handleRequest(
                event(BODY + "{}", Map.of("content-type", "application/json")), null
        );

        assertEquals(400, duplicate.getStatusCode());
        assertEquals(400, trailingValue.getStatusCode());
    }

    @Test
    void rejectsUnknownFieldsAndInvalidFieldTypes() {
        InterestApiHandler handler = handler(mock(InterestRepository.class));
        String unknownField = BODY.stripTrailing().substring(0, BODY.stripTrailing().length() - 1)
                + ",\"fullAddress\":\"private\"}";
        String invalidType = BODY.replace("\"consent\":true", "\"consent\":\"yes\"");

        APIGatewayV2HTTPResponse unknown = handler.handleRequest(
                event(unknownField, Map.of("content-type", "application/json")), null
        );
        APIGatewayV2HTTPResponse wrongType = handler.handleRequest(
                event(invalidType, Map.of("content-type", "application/json")), null
        );

        assertEquals(400, unknown.getStatusCode());
        assertEquals(400, wrongType.getStatusCode());
        assertFalse(unknown.getBody().contains("private"));
    }

    @Test
    void rejectsPayloadsOverLimitFromHeadersAndDecodedBody() {
        InterestApiHandler handler = handler(mock(InterestRepository.class));
        APIGatewayV2HTTPEvent declaredLarge = event(BODY, Map.of(
                "content-type", "application/json", "content-length", "16385"
        ));
        String oversizedBody = " ".repeat(16 * 1024 + 1);
        APIGatewayV2HTTPEvent actualLarge = event(oversizedBody, Map.of("content-type", "application/json"));

        assertEquals(413, handler.handleRequest(declaredLarge, null).getStatusCode());
        assertEquals(413, handler.handleRequest(actualLarge, null).getStatusCode());
    }

    @Test
    void supportsBase64EncodedApiGatewayPayloads() {
        InterestRepository repository = mock(InterestRepository.class);
        when(repository.findRegistrationFingerprintByIdempotencyKey(Hashing.sha256("request-123")))
                .thenReturn(null);
        when(repository.existsByEmailHash(Hashing.sha256("alex@example.com"))).thenReturn(false);
        when(repository.saveIdempotently(any(), any(), any()))
                .thenReturn(InterestRepository.IdempotentSaveResult.CREATED);
        APIGatewayV2HTTPEvent request = event(Base64.getEncoder().encodeToString(BODY.getBytes()), Map.of(
                "content-type", "application/json"
        ));
        request.setIsBase64Encoded(true);

        APIGatewayV2HTTPResponse response = handler(repository).handleRequest(request, null);

        assertEquals(201, response.getStatusCode());
    }

    @Test
    void returnsConflictForDuplicateRegistrationWithoutExposingEmail() {
        InterestRepository repository = mock(InterestRepository.class);
        when(repository.findRegistrationFingerprintByIdempotencyKey(Hashing.sha256("request-123")))
                .thenReturn(null);
        when(repository.existsByEmailHash(Hashing.sha256("alex@example.com"))).thenReturn(true);

        APIGatewayV2HTTPResponse response = handler(repository).handleRequest(
                event(BODY, Map.of("content-type", "application/json")), null
        );

        assertEquals(409, response.getStatusCode());
        assertFalse(response.getBody().contains("alex@example.com"));
    }

    @Test
    void returnsConflictWhenAnIdempotencyKeyIsReusedForDifferentData() {
        InterestRepository repository = mock(InterestRepository.class);
        when(repository.findRegistrationFingerprintByIdempotencyKey(Hashing.sha256("request-123")))
                .thenReturn("different-request-fingerprint");

        APIGatewayV2HTTPResponse response = handler(repository).handleRequest(
                event(BODY, Map.of("content-type", "application/json")), null
        );

        assertEquals(409, response.getStatusCode());
        assertFalse(response.getBody().contains("alex@example.com"));
    }

    @Test
    void mapsPersistenceThrottlingToRetryableResponse() {
        InterestRepository repository = mock(InterestRepository.class);
        when(repository.findRegistrationFingerprintByIdempotencyKey(Hashing.sha256("request-123")))
                .thenThrow(DynamoDbException.builder()
                        .message("throttled request containing alex@example.com")
                        .awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("ProvisionedThroughputExceededException")
                                .build())
                        .build());

        Logger logger = Logger.getLogger(InterestApiHandler.class.getName());
        List<String> logMessages = new ArrayList<>();
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
        logger.addHandler(logCapture);
        try {
            APIGatewayV2HTTPResponse response = handler(repository).handleRequest(
                    event(BODY, Map.of("content-type", "application/json")), null
            );

            assertEquals(429, response.getStatusCode());
            assertTrue(response.getBody().contains("\"throttled\""));
            assertFalse(response.getBody().contains("alex@example.com"));
            assertTrue(logMessages.stream().noneMatch(message -> message.contains("alex@example.com")));
        } finally {
            logger.removeHandler(logCapture);
        }
    }

    @Test
    void mapsOtherPersistenceFailuresToGenericServiceUnavailable() {
        InterestRepository repository = mock(InterestRepository.class);
        when(repository.findRegistrationFingerprintByIdempotencyKey(Hashing.sha256("request-123")))
                .thenThrow(new IllegalStateException("failure for alex@example.com"));

        APIGatewayV2HTTPResponse response = handler(repository).handleRequest(
                event(BODY, Map.of("content-type", "application/json")), null
        );

        assertEquals(503, response.getStatusCode());
        assertFalse(response.getBody().contains("alex@example.com"));
        assertFalse(response.getBody().contains("failure"));
    }

    @Test
    void rejectsUnsupportedMethodsAndPaths() {
        InterestApiHandler handler = handler(mock(InterestRepository.class));

        assertEquals(405, handler.handleRequest(event("GET", "/v1/interests", BODY, Map.of(
                "content-type", "application/json"
        )), null).getStatusCode());
        assertEquals(404, handler.handleRequest(event("POST", "/v1/other", BODY, Map.of(
                "content-type", "application/json"
        )), null).getStatusCode());
    }

    private static InterestApiHandler handler(InterestRepository repository) {
        RegistrationService service = new RegistrationService(repository, Hashing::sha256, FIXED_CLOCK);
        return new InterestApiHandler(service);
    }

    private static APIGatewayV2HTTPEvent event(String body, Map<String, String> headers) {
        return event("POST", "/v1/interests", body, headers);
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
