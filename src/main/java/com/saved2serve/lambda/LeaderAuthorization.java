package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public final class LeaderAuthorization {
    public static final String LEADERS_GROUP = "Leaders";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String GROUPS_CLAIM = "cognito:groups";

    private LeaderAuthorization() {
    }

    public static boolean isAuthorized(
            APIGatewayV2HTTPEvent event,
            String requiredScope
    ) {
        if (event == null || requiredScope == null || requiredScope.isBlank()
                || event.getRequestContext() == null
                || event.getRequestContext().getAuthorizer() == null
                || event.getRequestContext().getAuthorizer().getJwt() == null) {
            return false;
        }

        APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT jwt =
                event.getRequestContext().getAuthorizer().getJwt();
        Map<String, String> claims = jwt.getClaims();
        List<String> scopes = jwt.getScopes();
        if (claims == null || scopes == null || !scopes.contains(requiredScope)) {
            return false;
        }

        return hasLeaderGroup(claims.get(GROUPS_CLAIM));
    }

    private static boolean hasLeaderGroup(String groupsClaim) {
        if (groupsClaim == null || groupsClaim.isBlank()) {
            return false;
        }
        if (LEADERS_GROUP.equals(groupsClaim)) {
            return true;
        }

        try {
            JsonNode groups = OBJECT_MAPPER.readTree(groupsClaim);
            if (groups == null || !groups.isArray()) {
                return false;
            }
            for (JsonNode group : groups) {
                if (group.isTextual() && LEADERS_GROUP.equals(group.textValue())) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException invalidGroupsClaim) {
            return false;
        }
    }
}
