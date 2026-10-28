package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiRouterHandlerTest {
    @Test
    void dispatchesRequestToMatchingRoute() {
        AtomicBoolean dispatched = new AtomicBoolean();
        APIGatewayV2HTTPResponse expected = response(202);
        ApiRouterHandler handler = new ApiRouterHandler(Map.of(
                "/v1/interests",
                (event, context) -> {
                    dispatched.set(true);
                    return expected;
                }
        ));

        APIGatewayV2HTTPResponse actual = handler.handleRequest(event("/v1/interests"), null);

        assertTrue(dispatched.get());
        assertEquals(expected, actual);
    }

    @Test
    void returnsNotFoundWithoutDispatchingUnknownRoute() {
        AtomicBoolean dispatched = new AtomicBoolean();
        ApiRouterHandler handler = new ApiRouterHandler(Map.of(
                "/v1/interests",
                (event, context) -> {
                    dispatched.set(true);
                    return response(200);
                }
        ));

        APIGatewayV2HTTPResponse response = handler.handleRequest(event("/unknown"), null);

        assertFalse(dispatched.get());
        assertEquals(404, response.getStatusCode());
        assertTrue(response.getBody().contains("\"not_found\""));
    }

    @Test
    void returnsBadRequestForNullEvent() {
        ApiRouterHandler handler = new ApiRouterHandler(Map.of());

        APIGatewayV2HTTPResponse response = handler.handleRequest(null, null);

        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("\"invalid_request\""));
    }

    private static APIGatewayV2HTTPEvent event(String path) {
        return APIGatewayV2HTTPEvent.builder().withRawPath(path).build();
    }

    private static APIGatewayV2HTTPResponse response(int statusCode) {
        return APIGatewayV2HTTPResponse.builder().withStatusCode(statusCode).build();
    }
}
