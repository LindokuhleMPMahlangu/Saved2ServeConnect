package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saved2serve.domain.ChurchDirectoryEntry;
import com.saved2serve.persistence.dynamodb.DynamoDbSaved2ServeRepository;
import com.saved2serve.service.ChurchDirectoryService;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kms.KmsClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

public final class ChurchDirectoryApiHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    private static final Logger LOGGER = Logger.getLogger(ChurchDirectoryApiHandler.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_QUERY_PARAMETERS = Set.of("areaCode");

    private final ChurchDirectoryService churchDirectoryService;

    public ChurchDirectoryApiHandler() {
        String tableName = requiredEnvironmentVariable("SAVED2SERVE_TABLE_NAME");
        String kmsKeyId = requiredEnvironmentVariable("SAVED2SERVE_KMS_KEY_ID");
        DynamoDbClient dynamoDbClient = DynamoDbClient.builder().build();
        KmsClient kmsClient = KmsClient.builder().build();
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                dynamoDbClient, kmsClient, tableName, kmsKeyId
        );
        this.churchDirectoryService = new ChurchDirectoryService(repository);
    }

    public ChurchDirectoryApiHandler(ChurchDirectoryService churchDirectoryService) {
        this.churchDirectoryService = Objects.requireNonNull(
                churchDirectoryService, "churchDirectoryService must not be null"
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
        if (!"GET".equalsIgnoreCase(method)) {
            return errorResponse(405, "method_not_allowed", "Only GET is supported.");
        }
        if (!"/v1/churches".equals(event.getRawPath())) {
            return errorResponse(404, "not_found", "Route not found.");
        }

        Map<String, String> queryParameters = event.getQueryStringParameters();
        if (queryParameters == null
                || queryParameters.size() != ALLOWED_QUERY_PARAMETERS.size()
                || !queryParameters.keySet().containsAll(ALLOWED_QUERY_PARAMETERS)) {
            return errorResponse(400, "invalid_request", "An areaCode query parameter is required.");
        }
        String areaCode = queryParameters.get("areaCode");
        if (areaCode == null || areaCode.isBlank()) {
            return errorResponse(400, "invalid_request", "An areaCode query parameter is required.");
        }

        try {
            List<ChurchDirectoryEntry> churches = churchDirectoryService.findByAreaCode(areaCode);
            return jsonResponse(200, Map.of("churches", churches));
        } catch (RuntimeException failure) {
            LOGGER.severe("Church directory lookup failed.");
            return errorResponse(503, "service_unavailable", "Church directory is temporarily unavailable.");
        }
    }

    private static APIGatewayV2HTTPResponse jsonResponse(int statusCode, Object body) {
        try {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(statusCode)
                    .withHeaders(Map.of(
                            "content-type", "application/json",
                            "cache-control", "no-store"
                    ))
                    .withBody(OBJECT_MAPPER.writeValueAsString(body))
                    .build();
        } catch (JsonProcessingException serializationFailure) {
            LOGGER.severe("Church directory response serialization failed.");
            return errorResponse(
                    503, "service_unavailable", "Church directory is temporarily unavailable."
            );
        }
    }

    private static APIGatewayV2HTTPResponse errorResponse(int statusCode, String code, String message) {
        return jsonResponse(statusCode, Map.of("error", code, "message", message));
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is not configured: " + name);
        }
        return value;
    }
}
