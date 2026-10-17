package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.saved2serve.domain.ChurchDirectoryRecord;
import com.saved2serve.domain.ChurchDirectoryStatus;
import com.saved2serve.repository.ChurchDirectoryRepository;
import com.saved2serve.service.ChurchDirectoryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
class ChurchDirectoryApiHandlerTest {
    @Test
    void returnsOnlyPublicChurchFieldsWithoutBearerTokenForAreaCodeLookup() {
        ChurchDirectoryApiHandler handler = handlerFor(areaCode -> List.of(record(
                "church-1", "00123", "Example Church", "https://example.org", "14 Example Road"
        )));

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(
                "GET", "/v1/churches", Map.of("areaCode", "00123")
        ), null);

        assertEquals(200, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().get("cache-control"));
        assertTrue(response.getBody().contains("\"churchId\":\"church-1\""));
        assertTrue(response.getBody().contains("\"displayName\":\"Example Church\""));
        assertTrue(response.getBody().contains("\"publicAddress\":\"14 Example Road\""));
        assertFalse(response.getBody().contains("APPROVED"));
        assertFalse(response.getBody().contains("sourceReference"));
    }

    @Test
    void returnsEmptyChurchListWhenAreaHasNoApprovedMatches() {
        ChurchDirectoryApiHandler handler = handlerFor(areaCode -> List.of());

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(
                "GET", "/v1/churches", Map.of("areaCode", "99999")
        ), null);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("\"churches\":[]"));
    }

    @Test
    void rejectsMissingBlankAndUnknownQueryParameters() {
        ChurchDirectoryApiHandler handler = handlerFor(areaCode -> List.of());

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
        ChurchDirectoryApiHandler handler = handlerFor(areaCode -> List.of());

        assertEquals(405, handler.handleRequest(
                event("POST", "/v1/churches", Map.of("areaCode", "00123")), null
        ).getStatusCode());
        assertEquals(404, handler.handleRequest(
                event("GET", "/v1/other", Map.of("areaCode", "00123")), null
        ).getStatusCode());
    }

    @Test
    void returnsGenericUnavailableResponseWhenLookupFails() {
        ChurchDirectoryApiHandler handler = handlerFor(areaCode -> {
            throw new IllegalStateException("internal database details");
        });

        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("GET", "/v1/churches", Map.of("areaCode", "00123")), null
        );

        assertEquals(503, response.getStatusCode());
        assertFalse(response.getBody().contains("internal database details"));
    }

    private static ChurchDirectoryApiHandler handlerFor(
            Function<String, List<ChurchDirectoryRecord>> lookup
    ) {
        ChurchDirectoryRepository repository = new ChurchDirectoryRepository() {
            @Override
            public List<ChurchDirectoryRecord> findApprovedByAreaCode(String areaCode) {
                return lookup.apply(areaCode);
            }

            @Override
            public void saveApproved(ChurchDirectoryRecord churchDirectoryRecord) {
                throw new UnsupportedOperationException();
            }
        };
        return new ChurchDirectoryApiHandler(new ChurchDirectoryService(repository));
    }

    private static ChurchDirectoryRecord record(
            String churchId,
            String areaCode,
            String displayName,
            String website,
            String address
    ) {
        return new ChurchDirectoryRecord(
                churchId, displayName, website, areaCode, ChurchDirectoryStatus.APPROVED,
                Instant.parse("2026-10-25T20:47:00Z"), "test-source", address
        );
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
