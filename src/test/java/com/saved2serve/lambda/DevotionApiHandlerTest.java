package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saved2serve.domain.DevotionLink;
import com.saved2serve.domain.DevotionLinkStatus;
import com.saved2serve.repository.DevotionLinkRepository;
import com.saved2serve.service.DevotionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevotionApiHandlerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-24T10:15:30Z");
    private static final Instant VERIFIED_AT = Instant.parse("2026-10-25T10:00:00Z");

    @Test
    void returnsOnlySafeFieldsForPublishedLinks() throws Exception {
        DevotionLinkRepository repository = new DevotionLinkRepository() {
            @Override
            public void saveApproved(DevotionLink devotionLink) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DevotionLink> findPublished() {
                return List.of(new DevotionLink(
                        "devotion-1",
                        "Weekly message",
                        "https://youtu.be/video123",
                        DevotionLinkStatus.APPROVED,
                        "internal-source-reference",
                        SUBMITTED_AT,
                        VERIFIED_AT,
                        "internal-reviewer",
                        "internal review note"
                ));
            }
        };
        DevotionApiHandler handler = new DevotionApiHandler(new DevotionService(repository));

        APIGatewayV2HTTPResponse response = handler.handleRequest(event("GET", "/v1/devotions", null), null);

        assertEquals(200, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().get("cache-control"));
        JsonNode body = OBJECT_MAPPER.readTree(response.getBody());
        JsonNode devotion = body.path("devotions").get(0);
        assertEquals(1, body.path("devotions").size());
        assertEquals("devotion-1", devotion.path("devotionId").asText());
        assertEquals("Weekly message", devotion.path("title").asText());
        assertEquals("https://youtu.be/video123", devotion.path("url").asText());
        assertEquals("YOUTUBE", devotion.path("platform").asText());
        assertEquals(4, devotion.size());
        assertFalse(response.getBody().contains("sourceReference"));
        assertFalse(response.getBody().contains("internal-reviewer"));
        assertFalse(response.getBody().contains("internal review note"));
    }

    @Test
    void returnsEmptyListWhenNoApprovedLinksExist() throws Exception {
        DevotionLinkRepository repository = new DevotionLinkRepository() {
            @Override
            public void saveApproved(DevotionLink devotionLink) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DevotionLink> findPublished() {
                return List.of();
            }
        };
        DevotionApiHandler handler = new DevotionApiHandler(new DevotionService(repository));

        APIGatewayV2HTTPResponse response = handler.handleRequest(event("GET", "/v1/devotions", null), null);

        assertEquals(200, response.getStatusCode());
        assertEquals(0, OBJECT_MAPPER.readTree(response.getBody()).path("devotions").size());
    }

    @Test
    void rejectsUnsupportedMethodPathAndQueryParameters() {
        DevotionApiHandler handler = new DevotionApiHandler(new DevotionService(emptyRepository()));

        assertEquals(405, handler.handleRequest(event("POST", "/v1/devotions", null), null).getStatusCode());
        assertEquals(404, handler.handleRequest(event("GET", "/v1/other", null), null).getStatusCode());
        assertEquals(400, handler.handleRequest(
                event("GET", "/v1/devotions", Map.of("status", "APPROVED")), null
        ).getStatusCode());
    }

    @Test
    void returnsGenericUnavailableResponseWhenRepositoryFails() {
        DevotionLinkRepository repository = new DevotionLinkRepository() {
            @Override
            public void saveApproved(DevotionLink devotionLink) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DevotionLink> findPublished() {
                throw new IllegalStateException("internal database details");
            }
        };
        DevotionApiHandler handler = new DevotionApiHandler(new DevotionService(repository));

        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("GET", "/v1/devotions", null), null
        );

        assertEquals(503, response.getStatusCode());
        assertFalse(response.getBody().contains("internal database details"));
        assertTrue(response.getBody().contains("service_unavailable"));
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

    private static DevotionLinkRepository emptyRepository() {
        return new DevotionLinkRepository() {
            @Override
            public void saveApproved(DevotionLink devotionLink) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<DevotionLink> findPublished() {
                return List.of();
            }
        };
    }
}
