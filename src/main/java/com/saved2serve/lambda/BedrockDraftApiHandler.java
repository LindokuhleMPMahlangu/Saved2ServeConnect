package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saved2serve.util.OperationalLog;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class BedrockDraftApiHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    );
    private static final String PATH = "/v1/admin/drafts";
    private static final String WRITE_SCOPE = "saved2serve/leader.write";
    private static final String CONTENT_TYPE = "application/json";
    private static final int MAX_PAYLOAD_BYTES = 2 * 1024;
    private static final Set<String> TOPICS = Set.of(
            "community_encouragement", "volunteer_invitation", "welcome_note", "prayer_support"
    );

    private final Function<String, String> draftGenerator;

    public BedrockDraftApiHandler() {
        String modelId = requiredEnvironmentVariable("SAVED2SERVE_BEDROCK_MODEL_ID");
        BedrockRuntimeClient client = BedrockRuntimeClient.builder().build();
        BedrockDraftGenerator generator = new BedrockDraftGenerator(client, modelId);
        draftGenerator = generator::generate;
    }

    BedrockDraftApiHandler(Function<String, String> draftGenerator) {
        this.draftGenerator = draftGenerator;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        if (event == null || !PATH.equals(event.getRawPath())) {
            return errorResponse(404, "not_found", "Route not found.");
        }
        String method = event.getRequestContext() == null || event.getRequestContext().getHttp() == null
                ? null
                : event.getRequestContext().getHttp().getMethod();
        if (!"POST".equalsIgnoreCase(method)) {
            return errorResponse(405, "method_not_allowed", "Method is not supported for this route.");
        }
        if (!LeaderAuthorization.isAuthorized(event, WRITE_SCOPE)) {
            return errorResponse(403, "forbidden", "Leader authorization is required.");
        }

        byte[] payload;
        try {
            payload = requestBody(event);
        } catch (IllegalArgumentException invalidBody) {
            return errorResponse(400, "invalid_request", "Request body must be valid JSON.");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            return errorResponse(413, "payload_too_large", "Request body exceeds the 2 KiB limit.");
        }
        String contentType = header(event.getHeaders(), "content-type");
        if (contentType == null || !CONTENT_TYPE.equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            return errorResponse(415, "unsupported_media_type", "Content-Type must be application/json.");
        }

        String topic;
        try {
            JsonNode request = OBJECT_MAPPER.readTree(payload);
            if (request == null || !request.isObject()
                    || request.size() != 1
                    || !request.has("topic")
                    || !request.get("topic").isTextual()) {
                return errorResponse(400, "invalid_request", "A supported topic is required.");
            }
            topic = request.get("topic").textValue();
            if (!TOPICS.contains(topic)) {
                return errorResponse(400, "invalid_request", "A supported topic is required.");
            }
        } catch (IOException invalidJson) {
            return errorResponse(400, "invalid_request", "Request body must be valid JSON.");
        }

        try {
            String draft = draftGenerator.apply(topic);
            if (draft == null || draft.isBlank() || draft.length() > 8_000) {
                throw new IllegalStateException("Model response did not contain a valid draft.");
            }
            return jsonResponse(200, new DraftResponse(draft, true));
        } catch (RuntimeException failure) {
            OperationalLog.error("BEDROCK_DRAFT_GENERATION_FAILED", Map.of(
                    "service", "bedrock-draft-api", "outcome", "error", "statusCode", 503
            ));
            return errorResponse(503, "service_unavailable", "A draft could not be generated. Try again later.");
        }
    }

    private static byte[] requestBody(APIGatewayV2HTTPEvent event) {
        String body = event.getBody();
        if (body == null) {
            throw new IllegalArgumentException("Request body is missing.");
        }
        if (Boolean.TRUE.equals(event.getIsBase64Encoded())) {
            try {
                return java.util.Base64.getDecoder().decode(body);
            } catch (IllegalArgumentException invalidBase64) {
                throw new IllegalArgumentException("Request body is invalid.", invalidBase64);
            }
        }
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static APIGatewayV2HTTPResponse jsonResponse(int statusCode, Object body) {
        try {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(statusCode)
                    .withHeaders(Map.of(
                            "content-type", CONTENT_TYPE,
                            "cache-control", "no-store"
                    ))
                    .withBody(OBJECT_MAPPER.writeValueAsString(body))
                    .build();
        } catch (IOException serializationFailure) {
            OperationalLog.error("BEDROCK_DRAFT_RESPONSE_SERIALIZATION_FAILED", Map.of(
                    "service", "bedrock-draft-api", "outcome", "error", "statusCode", 503
            ));
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(503)
                    .withHeaders(Map.of("cache-control", "no-store"))
                    .withBody("{\"error\":\"service_unavailable\",\"message\":\"The request could not be completed.\"}")
                    .build();
        }
    }

    private static APIGatewayV2HTTPResponse errorResponse(int status, String code, String message) {
        return jsonResponse(status, Map.of("error", code, "message", message));
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(entry -> name.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is not configured: " + name);
        }
        return value;
    }

    private record DraftResponse(String draft, boolean requiresLeaderReview) {
    }
}
