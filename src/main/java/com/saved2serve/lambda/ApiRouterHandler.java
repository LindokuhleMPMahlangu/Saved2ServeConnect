package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

import java.util.Map;
import java.util.function.BiFunction;

public final class ApiRouterHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final String LEADER_ENGAGEMENT_PATH = "/v1/admin/engagements";

    private final Map<String, BiFunction<APIGatewayV2HTTPEvent, Context, APIGatewayV2HTTPResponse>> routes;
    private InterestApiHandler interestApiHandler;
    private EngagementApiHandler engagementApiHandler;
    private ChurchDirectoryApiHandler churchDirectoryApiHandler;
    private DevotionApiHandler devotionApiHandler;
    private FollowUpWithdrawalApiHandler followUpWithdrawalApiHandler;
    private LeaderEngagementApiHandler leaderEngagementApiHandler;
    private BedrockDraftApiHandler bedrockDraftApiHandler;

    public ApiRouterHandler() {
        routes = Map.of(
                "/v1/interests", this::handleInterest,
                "/v1/engagements", this::handleEngagement,
                "/v1/churches", this::handleChurchDirectory,
                "/v1/devotions", this::handleDevotions,
                "/v1/follow-ups/withdraw", this::handleFollowUpWithdrawal,
                LEADER_ENGAGEMENT_PATH, this::handleLeaderEngagement,
                "/v1/admin/drafts", this::handleBedrockDraft
        );
    }

    ApiRouterHandler(
            Map<String, BiFunction<APIGatewayV2HTTPEvent, Context, APIGatewayV2HTTPResponse>> routes
    ) {
        this.routes = Map.copyOf(routes);
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        if (event == null) {
            return errorResponse(400, "invalid_request", "A valid request is required.");
        }

        String path = event.getRawPath();
        if (LEADER_ENGAGEMENT_PATH.equals(path)
                || (path != null && path.startsWith(LEADER_ENGAGEMENT_PATH + "/"))) {
            return routes.get(LEADER_ENGAGEMENT_PATH).apply(event, context);
        }

        BiFunction<APIGatewayV2HTTPEvent, Context, APIGatewayV2HTTPResponse> route = routes.get(path);
        return route == null ? errorResponse(404, "not_found", "Route not found.") : route.apply(event, context);
    }

    private APIGatewayV2HTTPResponse handleInterest(APIGatewayV2HTTPEvent event, Context context) {
        return interestApiHandler().handleRequest(event, context);
    }

    private APIGatewayV2HTTPResponse handleEngagement(APIGatewayV2HTTPEvent event, Context context) {
        return engagementApiHandler().handleRequest(event, context);
    }

    private APIGatewayV2HTTPResponse handleChurchDirectory(APIGatewayV2HTTPEvent event, Context context) {
        return churchDirectoryApiHandler().handleRequest(event, context);
    }

    private APIGatewayV2HTTPResponse handleDevotions(APIGatewayV2HTTPEvent event, Context context) {
        return devotionApiHandler().handleRequest(event, context);
    }

    private APIGatewayV2HTTPResponse handleFollowUpWithdrawal(APIGatewayV2HTTPEvent event, Context context) {
        return followUpWithdrawalApiHandler().handleRequest(event, context);
    }

    private APIGatewayV2HTTPResponse handleLeaderEngagement(APIGatewayV2HTTPEvent event, Context context) {
        return leaderEngagementHandler().handleRequest(event, context);
    }

    private APIGatewayV2HTTPResponse handleBedrockDraft(APIGatewayV2HTTPEvent event, Context context) {
        return bedrockDraftApiHandler().handleRequest(event, context);
    }

    private synchronized InterestApiHandler interestApiHandler() {
        if (interestApiHandler == null) {
            interestApiHandler = new InterestApiHandler();
        }
        return interestApiHandler;
    }

    private synchronized EngagementApiHandler engagementApiHandler() {
        if (engagementApiHandler == null) {
            engagementApiHandler = new EngagementApiHandler();
        }
        return engagementApiHandler;
    }

    private synchronized ChurchDirectoryApiHandler churchDirectoryApiHandler() {
        if (churchDirectoryApiHandler == null) {
            churchDirectoryApiHandler = new ChurchDirectoryApiHandler();
        }
        return churchDirectoryApiHandler;
    }

    private synchronized DevotionApiHandler devotionApiHandler() {
        if (devotionApiHandler == null) {
            devotionApiHandler = new DevotionApiHandler();
        }
        return devotionApiHandler;
    }

    private synchronized FollowUpWithdrawalApiHandler followUpWithdrawalApiHandler() {
        if (followUpWithdrawalApiHandler == null) {
            followUpWithdrawalApiHandler = new FollowUpWithdrawalApiHandler();
        }
        return followUpWithdrawalApiHandler;
    }

    private synchronized LeaderEngagementApiHandler leaderEngagementHandler() {
        if (leaderEngagementApiHandler == null) {
            leaderEngagementApiHandler = new LeaderEngagementApiHandler();
        }
        return leaderEngagementApiHandler;
    }

    private synchronized BedrockDraftApiHandler bedrockDraftApiHandler() {
        if (bedrockDraftApiHandler == null) {
            bedrockDraftApiHandler = new BedrockDraftApiHandler();
        }
        return bedrockDraftApiHandler;
    }

    private static APIGatewayV2HTTPResponse errorResponse(int statusCode, String code, String message) {
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(statusCode)
                .withHeaders(Map.of(
                        "content-type", "application/json",
                        "cache-control", "no-store"
                ))
                .withBody("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}")
                .build();
    }
}
