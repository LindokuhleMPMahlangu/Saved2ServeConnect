package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.saved2serve.domain.ChurchDirectoryEntry;
import com.saved2serve.service.ChurchDirectoryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChurchDirectoryApiHandlerTest {
    @Test
    void returnsOnlyPublicChurchFieldsForAreaCodeLookup() {
        ChurchDirectoryService service = mock(ChurchDirectoryService.class);
        when(service.findByAreaCode("00123")).thenReturn(List.of(
                new ChurchDirectoryEntry("church-1", "Example Church", "https://example.org")
        ));
        ChurchDirectoryApiHandler handler = new ChurchDirectoryApiHandler(service);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(
                "GET", "/v1/churches", Map.of("areaCode", "00123")
        ), null);

        assertEquals(200, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().get("cache-control"));
        assertTrue(response.getBody().contains("\"churchId\":\"church-1\""));
        assertTrue(response.getBody().contains("\"displayName\":\"Example Church\""));
        assertFalse(response.getBody().contains("APPROVED"));
        assertFalse(response.getBody().contains("sourceReference"));
        verify(service).findByAreaCode("00123");
    }

    @Test
    void returnsEmptyChurchListWhenAreaHasNoApprovedMatches() {
        ChurchDirectoryService service = mock(ChurchDirectoryService.class);
        when(service.findByAreaCode("99999")).thenReturn(List.of());
        ChurchDirectoryApiHandler handler = new ChurchDirectoryApiHandler(service);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(
                "GET", "/v1/churches", Map.of("areaCode", "99999")
        ), null);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("\"churches\":[]"));
        verify(service).findByAreaCode("99999");
    }

    @Test
    void rejectsMissingBlankAndUnknownQueryParameters() {
        ChurchDirectoryApiHandler handler = new ChurchDirectoryApiHandler(mock(ChurchDirectoryService.class));

        assertEquals(400, handler.handleRequest(event("GET", "/v1/churches", Map.of()), null).getStatusCode());
        assertEquals(400, handler.handleRequest(
                event("GET", "/v1/churches", Map.of("areaCode", "  ")), null
        ).getStatusCode());
        assertEquals(400, handler.handleRequest(
                event("GET", "/v1/churches", Map.of("areaCode", "00123", "region", "nearby")), null
        ).getStatusCode());
    }

    @Test
    void rejectsUnsupportedMethodAndPath() {
        ChurchDirectoryApiHandler handler = new ChurchDirectoryApiHandler(mock(ChurchDirectoryService.class));

        assertEquals(405, handler.handleRequest(
                event("POST", "/v1/churches", Map.of("areaCode", "00123")), null
        ).getStatusCode());
        assertEquals(404, handler.handleRequest(
                event("GET", "/v1/other", Map.of("areaCode", "00123")), null
        ).getStatusCode());
    }

    @Test
    void returnsGenericUnavailableResponseWhenLookupFails() {
        ChurchDirectoryService service = mock(ChurchDirectoryService.class);
        when(service.findByAreaCode("00123")).thenThrow(new IllegalStateException("internal database details"));
        ChurchDirectoryApiHandler handler = new ChurchDirectoryApiHandler(service);

        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("GET", "/v1/churches", Map.of("areaCode", "00123")), null
        );

        assertEquals(503, response.getStatusCode());
        assertFalse(response.getBody().contains("internal database details"));
    }

    private static APIGatewayV2HTTPEvent event(
            String method,
            String path,
            Map<String, String> queryParameters
    ) {
        return APIGatewayV2HTTPEvent.builder()
                .withRawPath(path)
                .withQueryStringParameters(queryParameters)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder().withMethod(method).build())
                        .build())
                .build();
    }
}
