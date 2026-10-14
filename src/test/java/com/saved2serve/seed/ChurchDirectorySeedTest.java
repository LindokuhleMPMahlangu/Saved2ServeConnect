package com.saved2serve.seed;

import com.saved2serve.domain.ChurchDirectoryRecord;
import com.saved2serve.persistence.dynamodb.DynamoDbRecordMapper;
import com.saved2serve.repository.ChurchDirectoryRepository;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.regions.Region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChurchDirectorySeedTest {
    @Test
    void seedsTheOwnerConfirmedChurchMapping() {
        ChurchDirectoryRepository repository = mock(ChurchDirectoryRepository.class);

        ChurchDirectorySeed.seed(repository);

        verify(repository).saveApproved(ChurchDirectorySeed.HOUSE_OF_TREASURES);
        ChurchDirectoryRecord church = ChurchDirectorySeed.HOUSE_OF_TREASURES;
        assertEquals("1800", church.areaCode());
        assertEquals("14 Impala Road, Rispark, Johannesburg South, 2091, South Africa",
                church.publicAddress());
    }

    @Test
    void seedRecordRoundTripsThroughDynamoDbMapper() {
        try (KmsClient kmsClient = KmsClient.builder().region(Region.US_EAST_1).build()) {
            DynamoDbRecordMapper mapper = new DynamoDbRecordMapper(kmsClient, "alias/test-email-key");

            var item = mapper.toItem(ChurchDirectorySeed.HOUSE_OF_TREASURES);

            assertEquals("AREA#1800", item.get("GSI2PK").s());
            assertEquals(ChurchDirectorySeed.HOUSE_OF_TREASURES,
                    mapper.toChurchDirectoryRecord(item));
            assertTrue(item.containsKey("publicAddress"));
        }
    }
}
