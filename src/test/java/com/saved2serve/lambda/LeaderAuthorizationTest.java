package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderAuthorizationTest {
    private static final String READ_SCOPE = "saved2serve/leader.read";

    @Test
    void authorizesLeaderWithRequiredScopeFromGatewayJwtContext() {
        APIGatewayV2HTTPEvent leader = event(
                Map.of("cognito:groups", "[\"Leaders\",\"OtherGroup\"]"),
                List.of(READ_SCOPE)
        );

        assertTrue(LeaderAuthorization.isAuthorized(leader, READ_SCOPE));
        assertFalse(LeaderAuthorization.isAuthorized(leader, "saved2serve/leader.write"));
    }

    @Test
    void deniesMissingOrInvalidBearerTokenWithoutGatewayJwtContext() {
        APIGatewayV2HTTPEvent noToken = eventWithHeaders(Map.of());
        APIGatewayV2HTTPEvent invalidToken = eventWithHeaders(
                Map.of("Authorization", "Bearer not-a-valid-jwt")
        );

        assertFalse(LeaderAuthorization.isAuthorized(noToken, READ_SCOPE));
        assertFalse(LeaderAuthorization.isAuthorized(invalidToken, READ_SCOPE));
    }

    @Test
    void deniesMissingAuthorizerClaimsAndScopes() {
        assertFalse(LeaderAuthorization.isAuthorized(null, READ_SCOPE));
        assertFalse(LeaderAuthorization.isAuthorized(eventWithoutAuthorizer(), READ_SCOPE));
        assertFalse(LeaderAuthorization.isAuthorized(event(null, List.of(READ_SCOPE)), READ_SCOPE));
        assertFalse(LeaderAuthorization.isAuthorized(
                event(Map.of("cognito:groups", "[\"Leaders\"]"), null),
                READ_SCOPE
        ));
        assertFalse(LeaderAuthorization.isAuthorized(
                event(Map.of("cognito:groups", "[\"Leaders\"]"), List.of(READ_SCOPE)),
                " "
        ));
    }

    @Test
    void deniesNonLeadersAndMalformedOrNonExactGroupClaims() {
        assertFalse(LeaderAuthorization.isAuthorized(
                event(Map.of("cognito:groups", "[\"Members\"]"), List.of(READ_SCOPE)),
                READ_SCOPE
        ));
        assertFalse(LeaderAuthorization.isAuthorized(
                event(Map.of("cognito:groups", "[\"NotLeaders\"]"), List.of(READ_SCOPE)),
                READ_SCOPE
        ));
        assertFalse(LeaderAuthorization.isAuthorized(
                event(Map.of("cognito:groups", "Leaders,Members"), List.of(READ_SCOPE)),
                READ_SCOPE
        ));
        assertFalse(LeaderAuthorization.isAuthorized(
                event(Map.of("cognito:groups", "[\"Leaders\""), List.of(READ_SCOPE)),
                READ_SCOPE
        ));
    }

    @Test
    void acceptsSingleLeaderGroupClaim() {
        assertTrue(LeaderAuthorization.isAuthorized(
                event(Map.of("cognito:groups", "Leaders"), List.of(READ_SCOPE)),
                READ_SCOPE
        ));
    }

    private static APIGatewayV2HTTPEvent event(
            Map<String, String> claims,
            List<String> scopes
    ) {
        APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT jwt =
                APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT.builder()
                        .withClaims(claims)
                        .withScopes(scopes)
                        .build();
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer =
                APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
                        .withJwt(jwt)
                        .build();
        return APIGatewayV2HTTPEvent.builder()
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder()
                        .withAuthorizer(authorizer)
                        .build())
                .build();
    }

    private static APIGatewayV2HTTPEvent eventWithoutAuthorizer() {
        return APIGatewayV2HTTPEvent.builder()
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder().build())
                .build();
    }

    private static APIGatewayV2HTTPEvent eventWithHeaders(Map<String, String> headers) {
        return APIGatewayV2HTTPEvent.builder()
                .withHeaders(headers)
                .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder().build())
                .build();
    }
}
