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
import com.saved2serve.domain.Engagement;
import com.saved2serve.domain.SubmissionStatus;
import com.saved2serve.persistence.dynamodb.DynamoDbSaved2ServeRepository;
import com.saved2serve.repository.EngagementQueueEntry;
import com.saved2serve.repository.EngagementRepository;
import com.saved2serve.repository.Page;
import com.saved2serve.service.EngagementService;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kms.KmsClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public final class LeaderEngagementApiHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final Logger LOGGER = Logger.getLogger(LeaderEngagementApiHandler.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    );
    private static final String BASE_PATH = "/v1/admin/engagements";
    private static final String READ_SCOPE = "saved2serve/leader.read";
    private static final String WRITE_SCOPE = "saved2serve/leader.write";
    private static final String CONTENT_TYPE = "application/json";
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final Set<String> QUEUE_PARAMETERS = Set.of("status", "from", "to", "limit", "cursor");

    private final EngagementService engagementService;

    public LeaderEngagementApiHandler() {
        String tableName = requiredEnvironmentVariable("SAVED2SERVE_TABLE_NAME");
        String kmsKeyId = requiredEnvironmentVariable("SAVED2SERVE_KMS_KEY_ID");
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                DynamoDbClient.builder().build(), KmsClient.builder().build(), tableName, kmsKeyId
        );
        engagementService = new EngagementService(repository);
    }

    public LeaderEngagementApiHandler(EngagementService engagementService) {
        this.engagementService = Objects.requireNonNull(
                engagementService, "engagementService must not be null"
        );
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        if (event == null) {
            return errorResponse(400, "invalid_request", "A valid request is required.");
        }
        String method = event.getRequestContext() == null || event.getRequestContext().getHttp() == null
                ? null
                : event.getRequestContext().getHttp().getMethod();
        String path = event.getRawPath();
        if ("GET".equalsIgnoreCase(method) && BASE_PATH.equals(path)) {
            if (!LeaderAuthorization.isAuthorized(event, READ_SCOPE)) {
                return errorResponse(403, "forbidden", "Leader authorization is required.");
            }
            return queue(event);
        }
        if ("PATCH".equalsIgnoreCase(method) && path != null && path.startsWith(BASE_PATH + "/")) {
            String engagementId = reviewId(method, path);
            if (engagementId == null) {
                return errorResponse(400, "invalid_request", "Engagement ID is invalid.");
            }
            if (!LeaderAuthorization.isAuthorized(event, WRITE_SCOPE)) {
                return errorResponse(403, "forbidden", "Leader authorization is required.");
            }
            String reviewerId = reviewerId(event);
            if (reviewerId == null) {
                return errorResponse(403, "forbidden", "Leader authorization is required.");
            }
            return review(event, engagementId, reviewerId);
        }
        if (!BASE_PATH.equals(path) && (path == null || !path.startsWith(BASE_PATH + "/"))) {
            return errorResponse(404, "not_found", "Route not found.");
        }
        return errorResponse(405, "method_not_allowed", "Method is not supported for this route.");
    }

    private APIGatewayV2HTTPResponse queue(APIGatewayV2HTTPEvent event) {
        Map<String, String> parameters = event.getQueryStringParameters();
        if (parameters != null && parameters.keySet().stream().anyMatch(key -> !QUEUE_PARAMETERS.contains(key))) {
            return errorResponse(400, "invalid_request", "A query parameter is not supported.");
        }
        try {
            SubmissionStatus status = parseStatus(parameters == null ? null : parameters.get("status"));
            Instant from = parseInstant(parameters == null ? null : parameters.get("from"), "from");
            Instant to = parseInstant(parameters == null ? null : parameters.get("to"), "to");
            int pageSize = parsePageSize(parameters == null ? null : parameters.get("limit"));
            String cursor = parameters == null ? null : parameters.get("cursor");
            Page<EngagementQueueEntry> page = engagementService.findQueue(status, from, to, pageSize, cursor);
            return jsonResponse(200, new QueueResponse(
                    page.items().stream().map(LeaderEngagementApiHandler::toQueueItem).toList(),
                    page.nextCursor()
            ));
        } catch (IllegalArgumentException invalidQuery) {
            return errorResponse(400, "invalid_request", safeMessage(invalidQuery));
        } catch (RuntimeException failure) {
            LOGGER.severe("Leader engagement queue lookup failed.");
            return errorResponse(503, "service_unavailable", "The review queue is temporarily unavailable.");
        }
    }

    private APIGatewayV2HTTPResponse review(
            APIGatewayV2HTTPEvent event,
            String engagementId,
            String reviewerId
    ) {
        String contentType = header(event.getHeaders(), "content-type");
        if (contentType == null || !CONTENT_TYPE.equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            return errorResponse(415, "unsupported_media_type", "Content-Type must be application/json.");
        }
        String contentLength = header(event.getHeaders(), "content-length");
        if (!hasValidContentLength(contentLength)) {
            return errorResponse(400, "invalid_request", "Content-Length is invalid.");
        }
        if (exceedsPayloadLimit(contentLength)) {
            return errorResponse(413, "payload_too_large", "Request body exceeds the 16 KiB limit.");
        }
        byte[] payload;
        try {
            payload = requestBody(event);
        } catch (IllegalArgumentException invalidBase64) {
            return errorResponse(400, "invalid_request", "Request body must be valid JSON.");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            return errorResponse(413, "payload_too_large", "Request body exceeds the 16 KiB limit.");
        }

        SubmissionStatus status;
        try {
            status = parseReviewStatus(payload);
        } catch (IOException invalidJson) {
            return errorResponse(400, "invalid_request", "Request body must be valid JSON.");
        } catch (IllegalArgumentException invalidRequest) {
            return errorResponse(400, "invalid_request", safeMessage(invalidRequest));
        }
        try {
            EngagementRepository.ReviewResult result = engagementService.review(engagementId, status, reviewerId);
            return switch (result) {
                case UPDATED -> jsonResponse(200, new ReviewResponse(status.name()));
                case NOT_FOUND -> errorResponse(404, "not_found", "Engagement was not found.");
                case INVALID_TRANSITION ->
                        errorResponse(409, "invalid_transition", "Engagement has already been reviewed.");
            };
        } catch (IllegalArgumentException invalidRequest) {
            return errorResponse(400, "invalid_request", safeMessage(invalidRequest));
        } catch (RuntimeException failure) {
            LOGGER.severe("Leader engagement review failed.");
            return errorResponse(503, "service_unavailable", "Engagement could not be reviewed.");
        }
    }

    private static SubmissionStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return SubmissionStatus.PENDING_REVIEW;
        }
        try {
            return SubmissionStatus.valueOf(value);
        } catch (IllegalArgumentException invalidStatus) {
            throw new IllegalArgumentException("status must be PENDING_REVIEW, APPROVED, or REJECTED.");
        }
    }

    private static SubmissionStatus parseReviewStatus(byte[] payload) throws IOException {
        JsonNode root;
        try (JsonParser parser = OBJECT_MAPPER.getFactory().createParser(payload)) {
            root = OBJECT_MAPPER.readTree(parser);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("Request body must contain one JSON value.");
            }
        }
        if (root == null || !root.isObject() || root.size() != 1 || !root.has("status")
                || !root.get("status").isTextual()) {
            throw new IllegalArgumentException("Request body must contain only a string status.");
        }
        String value = root.get("status").textValue();
        if (!SubmissionStatus.APPROVED.name().equals(value) && !SubmissionStatus.REJECTED.name().equals(value)) {
            throw new IllegalArgumentException("status must be APPROVED or REJECTED.");
        }
        return SubmissionStatus.valueOf(value);
    }

    private static Instant parseInstant(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalidInstant) {
            throw new IllegalArgumentException(name + " must be an ISO-8601 UTC instant.");
        }
    }

    private static int parsePageSize(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PAGE_SIZE;
        }
        try {
            int pageSize = Integer.parseInt(value);
            if (pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100.");
            }
            return pageSize;
        } catch (NumberFormatException invalidSize) {
            throw new IllegalArgumentException("limit must be an integer between 1 and 100.");
        }
    }

    private static byte[] requestBody(APIGatewayV2HTTPEvent event) {
        String body = event.getBody();
        if (body == null) {
            return new byte[0];
        }
        return Boolean.TRUE.equals(event.getIsBase64Encoded())
                ? Base64.getDecoder().decode(body)
                : body.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean hasValidContentLength(String contentLength) {
        if (contentLength == null) {
            return true;
        }
        try {
            return Long.parseLong(contentLength) >= 0;
        } catch (NumberFormatException invalidLength) {
            return false;
        }
    }

    private static boolean exceedsPayloadLimit(String contentLength) {
        return contentLength != null && Long.parseLong(contentLength) > MAX_PAYLOAD_BYTES;
    }

    private static String reviewId(String method, String path) {
        String prefix = BASE_PATH + "/";
        if (!"PATCH".equalsIgnoreCase(method) || path == null || !path.startsWith(prefix)) {
            return null;
        }
        String id = path.substring(prefix.length());
        if (id.isBlank() || id.contains("/")) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(id);
            return parsed.toString().equalsIgnoreCase(id) ? parsed.toString() : null;
        } catch (IllegalArgumentException invalidId) {
            return null;
        }
    }

    private static String reviewerId(APIGatewayV2HTTPEvent event) {
        if (event.getRequestContext() == null || event.getRequestContext().getAuthorizer() == null
                || event.getRequestContext().getAuthorizer().getJwt() == null
                || event.getRequestContext().getAuthorizer().getJwt().getClaims() == null) {
            return null;
        }
        String subject = event.getRequestContext().getAuthorizer().getJwt().getClaims().get("sub");
        return subject == null || subject.isBlank() ? null : subject;
    }

    private static QueueItem toQueueItem(EngagementQueueEntry entry) {
        Engagement engagement = entry.engagement();
        return new QueueItem(
                entry.engagementId(),
                entry.createdAt().toString(),
                engagement.name(),
                engagement.email(),
                engagement.areaCode(),
                engagement.phone(),
                engagement.consent(),
                engagement.type().name(),
                engagement.message(),
                engagement.status().name()
        );
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
            LOGGER.severe("Leader engagement response serialization failed.");
            return errorResponse(503, "service_unavailable", "The request could not be completed.");
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

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null ? "Request is invalid." : message;
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is not configured: " + name);
        }
        return value;
    }

    private record QueueResponse(List<QueueItem> items, String nextCursor) {
    }

    private record QueueItem(
            String engagementId,
            String createdAt,
            String name,
            String email,
            String areaCode,
            String phone,
            boolean consent,
            String type,
            String message,
            String status
    ) {
    }

    private record ReviewResponse(String status) {
    }
}
