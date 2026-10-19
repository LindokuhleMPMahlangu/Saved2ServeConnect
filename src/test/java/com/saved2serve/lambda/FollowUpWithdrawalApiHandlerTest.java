package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.saved2serve.domain.FollowUpRecordType;
import com.saved2serve.repository.FollowUpRepository;
import com.saved2serve.service.FollowUpUnsubscribeToken;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class FollowUpWithdrawalApiHandlerTest {
    private static final String RECORD_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void validSignedWithdrawalChangesConsentAndReturnsNonEnumeratingResponse() {
        FollowUpRepository repository = mock(FollowUpRepository.class);
        String token = new FollowUpUnsubscribeToken(SECRET)
                .issue(RECORD_ID, FollowUpRecordType.ENGAGEMENT);
        FollowUpWithdrawalApiHandler handler = new FollowUpWithdrawalApiHandler(
                repository, new FollowUpUnsubscribeToken(SECRET)
        );

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(
                "{\"recordId\":\"" + RECORD_ID + "\",\"recordType\":\"ENGAGEMENT\",\"token\":\"" + token + "\"}"
        ), null);

        assertEquals(202, response.getStatusCode());
        assertEquals("{\"status\":\"request_received\"}", response.getBody());
        assertEquals("no-store", response.getHeaders().get("cache-control"));
        verify(repository).withdrawConsent(RECORD_ID, FollowUpRecordType.ENGAGEMENT);
    }

    @Test
    void invalidSignatureDoesNotAccessRepositoryAndDoesNotRevealRegistrationState() {
        FollowUpRepository repository = mock(FollowUpRepository.class);
        FollowUpWithdrawalApiHandler handler = new FollowUpWithdrawalApiHandler(
                repository, new FollowUpUnsubscribeToken(SECRET)
        );

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(
                "{\"recordId\":\"" + RECORD_ID + "\",\"recordType\":\"ENGAGEMENT\",\"token\":\"invalid\"}"
        ), null);

        assertEquals(202, response.getStatusCode());
        assertEquals("{\"status\":\"request_received\"}", response.getBody());
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsMalformedRequestsWithoutRepositoryAccess() {
        FollowUpRepository repository = mock(FollowUpRepository.class);
        FollowUpWithdrawalApiHandler handler = new FollowUpWithdrawalApiHandler(
                repository, new FollowUpUnsubscribeToken(SECRET)
        );

        APIGatewayV2HTTPResponse response = handler.handleRequest(event(
                "{\"recordId\":\"not-a-uuid\",\"recordType\":\"ENGAGEMENT\",\"token\":\"token\"}"
        ), null);

        assertEquals(400, response.getStatusCode());
        verifyNoInteractions(repository);
    }

    private static APIGatewayV2HTTPEvent event(String body) {
        return APIGatewayV2HTTPEvent.builder()
                .withRawPath("/v1/follow-ups/withdraw")
                .withHeaders(Map.of("content-type", "application/json"))
                .withBody(body)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withHttp(APIGatewayV2HTTPEvent.RequestContext.Http.builder().withMethod("POST").build())
                        .build())
                .build();
    }
}
