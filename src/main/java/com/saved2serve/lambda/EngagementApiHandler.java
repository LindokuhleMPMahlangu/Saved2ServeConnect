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
import com.saved2serve.domain.EngagementRequest;
import com.saved2serve.domain.EngagementType;
import com.saved2serve.persistence.dynamodb.DynamoDbSaved2ServeRepository;
import com.saved2serve.service.EngagementService;
import com.saved2serve.validation.Saved2ServeValidator;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kms.KmsClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

public final class EngagementApiHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final Logger LOGGER = Logger.getLogger(EngagementApiHandler.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    );
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    private static final String CONTENT_TYPE = "application/json";
    private static final Set<String> THROTTLING_ERROR_CODES = Set.of(
            "ProvisionedThroughputExceededException",
            "RequestLimitExceeded",
            "ThrottlingException",
            "TooManyRequestsException"
    );

    private final EngagementService engagementService;

    public EngagementApiHandler() {
        String tableName = requiredEnvironmentVariable("SAVED2SERVE_TABLE_NAME");
        String kmsKeyId = requiredEnvironmentVariable("SAVED2SERVE_KMS_KEY_ID");
        DynamoDbClient dynamoDbClient = DynamoDbClient.builder().build();
        KmsClient kmsClient = KmsClient.builder().build();
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, tableName, kmsKeyId
        );
        this.engagementService = new EngagementService(repository);
    }

    public EngagementApiHandler(EngagementService engagementService) {
        this.engagementService = Objects.requireNonNull(
                engagementService, "engagementService must not be null"
        );
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        if (event == null) {
            return response(400, "invalid_request", "A valid request is required.");
        }
        String method = event.getRequestContext() == null || event.getRequestContext().getHttp() == null
                ? null
                : event.getRequestContext().getHttp().getMethod();
        if (!"POST".equalsIgnoreCase(method)) {
            return response(405, "method_not_allowed", "Only POST is supported.");
        }
        if (!"/v1/engagements".equals(event.getRawPath())) {
            return response(404, "not_found", "Route not found.");
        }

        String contentType = header(event.getHeaders(), "content-type");
        if (contentType == null || !CONTENT_TYPE.equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            return response(415, "unsupported_media_type", "Content-Type must be application/json.");
        }
        String contentLength = header(event.getHeaders(), "content-length");
        if (!hasValidContentLength(contentLength)) {
            return response(400, "invalid_request", "Content-Length is invalid.");
        }
        if (exceedsPayloadLimit(contentLength)) {
            return response(413, "payload_too_large", "Request body exceeds the 16 KiB limit.");
        }

        byte[] payload;
        try {
            payload = requestBody(event);
        } catch (IllegalArgumentException invalidBase64) {
            return response(400, "invalid_request", "Request body must be valid JSON.");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            return response(413, "payload_too_large", "Request body exceeds the 16 KiB limit.");
        }

        EngagementRequest request;
        try {
            request = parseRequest(payload);
        } catch (IOException invalidJson) {
            return response(400, "invalid_request", "Request body must be valid JSON.");
        } catch (IllegalArgumentException invalidRequest) {
            return response(400, "invalid_request", safeMessage(invalidRequest));
        }

        try {
            Saved2ServeValidator.ValidationResult result = engagementService.submit(request);
            if (!result.valid()) {
                return response(400, "invalid_request", result.message());
            }
            return response(201, "{\"status\":\"accepted\"}");
        } catch (EngagementService.EngagementPersistenceException failure) {
            if (isThrottled(failure)) {
                LOGGER.warning("Engagement submission was throttled.");
                return response(429, "throttled", "Service is temporarily busy. Please try again.");
            }
            LOGGER.severe("Engagement persistence failed.");
            return response(503, "service_unavailable", "Engagement could not be submitted. Please try again.");
        }
    }

    private static EngagementRequest parseRequest(byte[] payload) throws IOException {
        JsonNode root;
        try (JsonParser parser = OBJECT_MAPPER.getFactory().createParser(payload)) {
            root = OBJECT_MAPPER.readTree(parser);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("Request body must contain one JSON value.");
            }
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        root.fieldNames().forEachRemaining(field -> {
            if (!EngagementRequest.allowsField(field)) {
                throw new IllegalArgumentException("Unknown request field.");
            }
        });
        return new EngagementRequest(
                stringField(root, "name", true),
                stringField(root, "email", true),
                stringField(root, "areaCode", true),
                stringField(root, "phone", false),
                booleanField(root, "consent"),
                engagementType(root),
                stringField(root, "message", true)
        );
    }

    private static String stringField(JsonNode root, String name, boolean required) {
        JsonNode value = root.get(name);
        if (value == null && !required) {
            return "";
        }
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Field '" + name + "' must be a string.");
        }
        return value.textValue();
    }

    private static boolean booleanField(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException("Field '" + name + "' must be a boolean.");
        }
        return value.booleanValue();
    }

    private static EngagementType engagementType(JsonNode root) {
        String value = stringField(root, "type", true);
        try {
            return EngagementType.valueOf(value);
        } catch (IllegalArgumentException invalidType) {
            throw new IllegalArgumentException("Engagement type is invalid.");
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

    private static boolean isThrottled(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AwsServiceException serviceException
                    && serviceException.awsErrorDetails() != null
                    && serviceException.awsErrorDetails().errorCode() != null
                    && THROTTLING_ERROR_CODES.contains(serviceException.awsErrorDetails().errorCode())) {
                return true;
            }
        }
        return false;
    }

    private static String safeMessage(IllegalArgumentException exception) {
        String message = exception.getMessage();
        return message == null ? "Request body is invalid." : message;
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is not configured: " + name);
        }
        return value;
    }

    private static APIGatewayV2HTTPResponse response(int statusCode, String error, String message) {
        String body = "{\"error\":\"" + escapeJson(error) + "\",\"message\":\"" + escapeJson(message) + "\"}";
        return response(statusCode, body);
    }

    private static APIGatewayV2HTTPResponse response(int statusCode, String body) {
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(statusCode)
                .withHeaders(Map.of(
                        "content-type", CONTENT_TYPE,
                        "cache-control", "no-store"
                ))
                .withBody(body)
                .build();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
