package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.EngagementType;
import com.saved2serve.domain.SubmissionStatus;
import com.saved2serve.repository.EngagementQueueEntry;
import com.saved2serve.repository.EngagementRepository;
import com.saved2serve.repository.Page;
import com.saved2serve.service.EngagementService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LeaderEngagementApiHandlerTest {
    private static final String ID = "11111111-1111-4111-8111-111111111111";
    private static final String LEADER_SUBJECT = "leader-subject";
    private static final String READ_SCOPE = "saved2serve/leader.read";
    private static final String WRITE_SCOPE = "saved2serve/leader.write";

    @Test
    void deniesUnauthenticatedQueueRequestsBeforeRepositoryAccess() {
        EngagementRepository repository = mock(EngagementRepository.class);
        LeaderEngagementApiHandler handler = handler(repository);

        APIGatewayV2HTTPResponse response = handler.handleRequest(
                event("GET", "/v1/admin/engagements", null, null, Map.of(), null), null
        );

        assertEquals(403, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().get("cache-control"));
        verifyNoInteractions(repository);
    }

    @Test
    void leaderQueueAppliesStatusTimePaginationAndReturnsPrivateDataOnlyOnAdminRoute() {
        EngagementRepository repository = mock(EngagementRepository.class);
        Instant createdAt = Instant.parse("2026-10-25T10:00:00Z");
        Engagement engagement = new Engagement(
                "Alex Smith", "alex@example.com", "1800", "5551234567", true,
                EngagementType.HELP_REQUEST, "Please contact me.", SubmissionStatus.REJECTED
        );
        when(repository.findQueue(
                eq(SubmissionStatus.REJECTED),
                eq(Instant.parse("2026-10-01T00:00:00Z")),
                eq(Instant.parse("2026-10-26T00:00:00Z")),
                eq(2),
                eq("opaque-cursor")
        )).thenReturn(new Page<>(
                List.of(new EngagementQueueEntry(ID, createdAt, engagement)), null
        ));
        LeaderEngagementApiHandler handler = handler(repository);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(
                "GET",
                "/v1/admin/engagements",
                null,
                null,
                Map.of(
                        "status", "REJECTED",
                        "from", "2026-10-01T00:00:00Z",
                        "to", "2026-10-26T00:00:00Z",
                        "limit", "2",
                        "cursor", "opaque-cursor"
                ),
                List.of(READ_SCOPE)
        ), null);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains(ID));
        assertTrue(response.getBody().contains("alex@example.com"));
        assertTrue(response.getBody().contains("Please contact me."));
        assertTrue(response.getBody().contains(createdAt.toString()));
        assertFalse(response.getBody().contains("\"error\""));
        verify(repository).findQueue(
                SubmissionStatus.REJECTED,
                Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-26T00:00:00Z"),
                2,
                "opaque-cursor"
        );
    }

    @Test
    void rejectsInvalidQueueFiltersWithoutRepositoryAccess() {
        EngagementRepository repository = mock(EngagementRepository.class);
        LeaderEngagementApiHandler handler = handler(repository);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(
                "GET",
                "/v1/admin/engagements",
                null,
                null,
                Map.of("from", "not-a-time"),
                List.of(READ_SCOPE)
        ), null);

        assertEquals(400, response.getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test
    void reviewUsesJwtSubjectAndRejectsInvalidStateTransitions() {
        EngagementRepository repository = mock(EngagementRepository.class);
        when(repository.review(
                eq(ID), eq(SubmissionStatus.APPROVED), eq(LEADER_SUBJECT), any(Instant.class)
        )).thenReturn(EngagementRepository.ReviewResult.UPDATED);
        when(repository.review(
                eq(ID), eq(SubmissionStatus.REJECTED), eq(LEADER_SUBJECT), any(Instant.class)
        )).thenReturn(EngagementRepository.ReviewResult.INVALID_TRANSITION);
        LeaderEngagementApiHandler handler = handler(repository);

        APIGatewayV2HTTPResponse approved = handler.handleRequest(event(
                "PATCH",
                "/v1/admin/engagements/" + ID,
                "{\"status\":\"APPROVED\"}",
                Map.of("content-type", "application/json"),
                null,
                List.of(WRITE_SCOPE)
        ), null);
        APIGatewayV2HTTPResponse conflict = handler.handleRequest(event(
                "PATCH",
                "/v1/admin/engagements/" + ID,
                "{\"status\":\"REJECTED\"}",
                Map.of("content-type", "application/json"),
                null,
                List.of(WRITE_SCOPE)
        ), null);
        APIGatewayV2HTTPResponse invalidTransition = handler.handleRequest(event(
                "PATCH",
                "/v1/admin/engagements/" + ID,
                "{\"status\":\"PENDING_REVIEW\"}",
                Map.of("content-type", "application/json"),
                null,
                List.of(WRITE_SCOPE)
        ), null);

        assertEquals(200, approved.getStatusCode());
        assertTrue(approved.getBody().contains("\"status\":\"APPROVED\""));
        assertEquals(409, conflict.getStatusCode());
        assertEquals(400, invalidTransition.getStatusCode());
        verify(repository).review(
                eq(ID), eq(SubmissionStatus.APPROVED), eq(LEADER_SUBJECT),
                eq(Instant.parse("2026-10-26T12:00:00Z"))
        );
        verify(repository).review(
                eq(ID), eq(SubmissionStatus.REJECTED), eq(LEADER_SUBJECT),
                eq(Instant.parse("2026-10-26T12:00:00Z"))
        );
    }

    @Test
    void deniesReviewWithoutWriteScopeAndDoesNotAcceptReviewerFromBody() {
        EngagementRepository repository = mock(EngagementRepository.class);
        LeaderEngagementApiHandler handler = handler(repository);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(
                "PATCH",
                "/v1/admin/engagements/" + ID,
                "{\"status\":\"APPROVED\",\"reviewedBy\":\"attacker\"}",
                Map.of("content-type", "application/json"),
                null,
                List.of(READ_SCOPE)
        ), null);

        assertEquals(403, response.getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test
    void reviewRequestRejectsCallerSuppliedAuditFields() {
        EngagementRepository repository = mock(EngagementRepository.class);
        LeaderEngagementApiHandler handler = handler(repository);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(
                "PATCH",
                "/v1/admin/engagements/" + ID,
                "{\"status\":\"APPROVED\",\"reviewedBy\":\"attacker\"}",
                Map.of("content-type", "application/json"),
                null,
                List.of(WRITE_SCOPE)
        ), null);

        assertEquals(400, response.getStatusCode());
        verifyNoInteractions(repository);
    }

    private static LeaderEngagementApiHandler handler(EngagementRepository repository) {
        return new LeaderEngagementApiHandler(new EngagementService(
                repository,
                Clock.fixed(Instant.parse("2026-10-26T12:00:00Z"), ZoneOffset.UTC)
        ));
    }

    private static APIGatewayV2HTTPEvent event(
            String method,
            String path,
            String body,
            Map<String, String> headers,
            Map<String, String> queryParameters,
            List<String> scopes
    ) {
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer = null;
        if (scopes != null) {
            authorizer = APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
                    .withJwt(APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT.builder()
                            .withClaims(Map.of(
                                    "cognito:groups", "[\"Leaders\"]",
                                    "sub", LEADER_SUBJECT
                            ))
                            .withScopes(scopes)
                            .build())
                    .build();
        }
        return APIGatewayV2HTTPEvent.builder()
                .withRawPath(path)
                .withBody(body)
                .withHeaders(headers)
                .withQueryStringParameters(queryParameters)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                                .withMethod(method)
                                .build())
                        .withAuthorizer(authorizer)
                        .build())
                .build();
    }
}
