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
import com.saved2serve.domain.FollowUpRecordType;
import com.saved2serve.persistence.dynamodb.DynamoDbSaved2ServeRepository;
import com.saved2serve.repository.FollowUpRepository;
import com.saved2serve.service.FollowUpUnsubscribeToken;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kms.KmsClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

public final class FollowUpWithdrawalApiHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    );
    private static final String PATH = "/v1/follow-ups/withdraw";
    private static final String CONTENT_TYPE = "application/json";
    private static final int MAX_PAYLOAD_BYTES = 4096;
    private static final Set<String> ALLOWED_FIELDS = Set.of("recordId", "recordType", "token");

    private final FollowUpRepository repository;
    private final FollowUpUnsubscribeToken tokenSigner;

    public FollowUpWithdrawalApiHandler() {
        this(
                new DynamoDbSaved2ServeRepository(
                        DynamoDbClient.builder().build(),
                        KmsClient.builder().build(),
                        requiredEnvironmentVariable("SAVED2SERVE_TABLE_NAME"),
                        requiredEnvironmentVariable("SAVED2SERVE_KMS_KEY_ID")
                ),
                new FollowUpUnsubscribeToken(
                        requiredEnvironmentVariable("SAVED2SERVE_UNSUBSCRIBE_SIGNING_SECRET")
                )
        );
    }

    public FollowUpWithdrawalApiHandler(
            FollowUpRepository repository,
            FollowUpUnsubscribeToken tokenSigner
    ) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository must not be null");
        this.tokenSigner = java.util.Objects.requireNonNull(tokenSigner, "tokenSigner must not be null");
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        if (event == null) {
            return error(400, "A valid request is required.");
        }
        String method = event.getRequestContext() == null || event.getRequestContext().getHttp() == null
                ? null
                : event.getRequestContext().getHttp().getMethod();
        if (!"POST".equalsIgnoreCase(method)) {
            return error(405, "Only POST is supported.");
        }
        if (!PATH.equals(event.getRawPath())) {
            return error(404, "Route not found.");
        }
        String contentType = header(event.getHeaders(), "content-type");
        if (contentType == null || !CONTENT_TYPE.equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            return error(415, "Content-Type must be application/json.");
        }

        byte[] payload;
        try {
            payload = requestBody(event);
        } catch (IllegalArgumentException invalidBase64) {
            return error(400, "Request body must be valid JSON.");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            return error(413, "Request body exceeds the 4 KiB limit.");
        }
        WithdrawalRequest request;
        try {
            request = parseRequest(payload);
        } catch (IOException | IllegalArgumentException invalidRequest) {
            return error(400, "A valid unsubscribe request is required.");
        }

        if (tokenSigner.verifies(request.token(), request.recordId(), request.recordType())) {
            repository.withdrawConsent(request.recordId(), request.recordType());
        }
        return jsonResponse(202, "{\"status\":\"request_received\"}");
    }

    private static WithdrawalRequest parseRequest(byte[] payload) throws IOException {
        JsonNode root;
        try (JsonParser parser = OBJECT_MAPPER.getFactory().createParser(payload)) {
            root = OBJECT_MAPPER.readTree(parser);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("Request must contain one JSON value.");
            }
        }
        if (root == null || !root.isObject() || root.size() != ALLOWED_FIELDS.size()) {
            throw new IllegalArgumentException("Unsubscribe request fields are invalid.");
        }
        root.fieldNames().forEachRemaining(field -> {
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unsubscribe request fields are invalid.");
            }
        });
        String recordId = requiredText(root, "recordId");
        String recordTypeValue = requiredText(root, "recordType");
        String token = requiredText(root, "token");
        if (!recordId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Unsubscribe request fields are invalid.");
        }
        FollowUpRecordType recordType = FollowUpRecordType.valueOf(recordTypeValue);
        return new WithdrawalRequest(recordId, recordType, token);
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Unsubscribe request fields are invalid.");
        }
        return value.textValue().trim();
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

    private static APIGatewayV2HTTPResponse error(int status, String message) {
        return jsonResponse(status, "{\"error\":\"invalid_request\",\"message\":\""
                + message.replace("\"", "\\\"") + "\"}");
    }

    private static APIGatewayV2HTTPResponse jsonResponse(int status, String body) {
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(status)
                .withHeaders(Map.of(
                        "content-type", CONTENT_TYPE,
                        "cache-control", "no-store"
                ))
                .withBody(body)
                .build();
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is not configured: " + name);
        }
        return value;
    }

    private record WithdrawalRequest(String recordId, FollowUpRecordType recordType, String token) {
    }
}
