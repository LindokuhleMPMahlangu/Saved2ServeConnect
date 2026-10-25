package com.saved2serve.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.saved2serve.persistence.dynamodb.DynamoDbSaved2ServeRepository;
import com.saved2serve.service.FollowUpDeliveryWorker;
import com.saved2serve.service.FollowUpUnsubscribeToken;
import com.saved2serve.service.SesFollowUpEmailSender;
import com.saved2serve.util.OperationalLog;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FollowUpDeliveryHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private final FollowUpDeliveryWorker worker;

    public FollowUpDeliveryHandler() {
        DynamoDbSaved2ServeRepository repository = new DynamoDbSaved2ServeRepository(
                DynamoDbClient.builder().build(),
                KmsClient.builder().build(),
                requiredEnvironmentVariable("SAVED2SERVE_TABLE_NAME"),
                requiredEnvironmentVariable("SAVED2SERVE_KMS_KEY_ID")
        );
        this.worker = new FollowUpDeliveryWorker(
                repository,
                new SesFollowUpEmailSender(
                        SesV2Client.builder().build(),
                        requiredEnvironmentVariable("SAVED2SERVE_SES_FROM_ADDRESS")
                ),
                new FollowUpUnsubscribeToken(
                        requiredEnvironmentVariable("SAVED2SERVE_UNSUBSCRIBE_SIGNING_SECRET")
                ),
                requiredEnvironmentVariable("SAVED2SERVE_UNSUBSCRIBE_URL")
        );
    }

    public FollowUpDeliveryHandler(FollowUpDeliveryWorker worker) {
        this.worker = java.util.Objects.requireNonNull(worker, "worker must not be null");
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        if (event == null || event.getRecords() == null || event.getRecords().isEmpty()) {
            return new SQSBatchResponse(List.of());
        }
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                worker.process(message.getBody());
            } catch (RuntimeException failure) {
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
            }
        }
        Map<String, Object> metrics = Map.of(
                "service", "follow-up-delivery",
                "messageCount", event.getRecords().size(),
                "successCount", event.getRecords().size() - failures.size(),
                "failureCount", failures.size()
        );
        if (failures.isEmpty()) {
            OperationalLog.info("FOLLOW_UP_BATCH_PROCESSED", metrics);
        } else {
            OperationalLog.warning("FOLLOW_UP_BATCH_PROCESSED", metrics);
        }
        return new SQSBatchResponse(failures);
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is not configured: " + name);
        }
        return value;
    }
}
