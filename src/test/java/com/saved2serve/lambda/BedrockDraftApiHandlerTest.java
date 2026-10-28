package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockDraftApiHandlerTest {
    private static final String WRITE_SCOPE = "saved2serve/leader.write";

    @Test
    void acceptsOnlyLeaderWriteRequestsAndReturnsReviewRequiredDraft() {
        AtomicReference<String> requestedTopic = new AtomicReference<>();
        BedrockDraftApiHandler handler = new BedrockDraftApiHandler(topic -> {
            requestedTopic.set(topic);
            return "A warm draft for leaders.";
        });

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(
                "POST", "{\"topic\":\"welcome_note\"}", Map.of("content-type", "application/json"),
                List.of(WRITE_SCOPE)
        ), null);

        assertEquals(200, response.getStatusCode());
        assertEquals("welcome_note", requestedTopic.get());
        assertTrue(response.getBody().contains("\"draft\":\"A warm draft for leaders.\""));
        assertTrue(response.getBody().contains("\"requiresLeaderReview\":true"));
        assertEquals("no-store", response.getHeaders().get("cache-control"));
    }

    @Test
    void rejectsMissingAuthorizationBeforeCallingTheModel() {
        AtomicReference<String> requestedTopic = new AtomicReference<>();
        BedrockDraftApiHandler handler = new BedrockDraftApiHandler(topic -> {
            requestedTopic.set(topic);
            return "Generated draft.";
        });

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(
                "POST", "{\"topic\":\"welcome_note\"}", Map.of("content-type", "application/json"),
                List.of("saved2serve/leader.read")
        ), null);

        assertEquals(403, response.getStatusCode());
        assertFalse(response.getBody().contains("draft"));
        assertNull(requestedTopic.get());
    }

    @Test
    void rejectsFreeTextUnsupportedTopicsAndExtraFields() {
        AtomicReference<String> requestedTopic = new AtomicReference<>();
        BedrockDraftApiHandler handler = new BedrockDraftApiHandler(topic -> {
            requestedTopic.set(topic);
            return "Generated draft.";
        });

        APIGatewayV2HTTPResponse freeText = handler.handleRequest(event(
                "POST", "{\"topic\":\"write to this private person@example.org\"}",
                Map.of("content-type", "application/json"), List.of(WRITE_SCOPE)
        ), null);
        APIGatewayV2HTTPResponse extraField = handler.handleRequest(event(
                "POST", "{\"topic\":\"welcome_note\",\"message\":\"private submission\"}",
                Map.of("content-type", "application/json"), List.of(WRITE_SCOPE)
        ), null);
        APIGatewayV2HTTPResponse duplicateField = handler.handleRequest(event(
                "POST", "{\"topic\":\"welcome_note\",\"topic\":\"prayer_support\"}",
                Map.of("content-type", "application/json"), List.of(WRITE_SCOPE)
        ), null);

        assertEquals(400, freeText.getStatusCode());
        assertEquals(400, extraField.getStatusCode());
        assertEquals(400, duplicateField.getStatusCode());
        assertNull(requestedTopic.get());
    }

    @Test
    void rejectsInvalidContentTypeAndNonPostMethods() {
        BedrockDraftApiHandler handler = new BedrockDraftApiHandler(topic -> "Draft");

        APIGatewayV2HTTPResponse unsupportedType = handler.handleRequest(event(
                "POST", "{\"topic\":\"welcome_note\"}", Map.of("content-type", "text/plain"),
                List.of(WRITE_SCOPE)
        ), null);
        APIGatewayV2HTTPResponse unsupportedMethod = handler.handleRequest(event(
                "GET", null, Map.of(), List.of(WRITE_SCOPE)
        ), null);

        assertEquals(415, unsupportedType.getStatusCode());
        assertEquals(405, unsupportedMethod.getStatusCode());
    }

    private static APIGatewayV2HTTPEvent event(
            String method,
            String body,
            Map<String, String> headers,
            List<String> scopes
    ) {
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer = scopes == null
                ? null
                : APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
                        .withJwt(APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT.builder()
                                .withClaims(Map.of("cognito:groups", "[\"Leaders\"]"))
                                .withScopes(scopes)
                                .build())
                        .build();
        return APIGatewayV2HTTPEvent.builder()
                .withRawPath("/v1/admin/drafts")
                .withBody(body)
                .withHeaders(headers)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                .withMethod(method)
                                .build())
                        .withAuthorizer(authorizer)
                        .build())
                .build();
    }
}
