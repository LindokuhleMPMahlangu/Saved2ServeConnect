package com.saved2serve.seed;

import com.saved2serve.domain.ChurchDirectoryRecord;
import com.saved2serve.domain.ChurchDirectoryStatus;
import com.saved2serve.persistence.dynamodb.DynamoDbSaved2ServeRepository;
import com.saved2serve.repository.ChurchDirectoryRepository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kms.KmsClient;

import java.time.Instant;
import java.util.Objects;

public final class ChurchDirectorySeed {
    public static final ChurchDirectoryRecord HOUSE_OF_TREASURES = new ChurchDirectoryRecord(
            "house-of-treasures-ministries",
            "House of Treasures Ministries",
            "https://houseoftreasuresministries.com",
            "1800",
            ChurchDirectoryStatus.APPROVED,
            Instant.parse("2026-10-25T20:47:00Z"),
            "project-owner-attestation:church-confirmed-service-area:1800",
            "14 Impala Road, Rispark, Johannesburg South, 2091, South Africa"
    );

    private ChurchDirectorySeed() {
    }

    public static void seed(ChurchDirectoryRepository repository) {
        Objects.requireNonNull(repository, "repository must not be null").saveApproved(HOUSE_OF_TREASURES);
    }

    public static void main(String[] args) {
        String tableName = requiredEnvironmentVariable("SAVED2SERVE_TABLE_NAME");
        String kmsKeyId = requiredEnvironmentVariable("SAVED2SERVE_KMS_KEY_ID");
        try (DynamoDbClient dynamoDbClient = DynamoDbClient.create();
             KmsClient kmsClient = KmsClient.create()) {
            ChurchDirectorySeed.seed(new DynamoDbSaved2ServeRepository(
                    dynamoDbClient, kmsClient, tableName, kmsKeyId
            ));
        }
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is not configured: " + name);
        }
        return value;
    }
}
