package com.saved2serve.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalLogTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void emitsStructuredFieldsAndRedactsSensitiveValues() throws Exception {
        String json = OperationalLog.format(Level.WARNING, "FOLLOW_UP_BATCH_PROCESSED", Map.of(
                "service", "follow-up-delivery",
                "failureCount", 1,
                "email", "person@example.org",
                "recordId", "private-record-id",
                "message", "private submission text",
                "requestBody", "private request body",
                "unapprovedField", "must not be logged"
        ));

        JsonNode record = OBJECT_MAPPER.readTree(json);
        assertEquals("FOLLOW_UP_BATCH_PROCESSED", record.path("event").asText());
        assertEquals("follow-up-delivery", record.path("service").asText());
        assertEquals(1, record.path("failureCount").asInt());
        assertEquals("[REDACTED]", record.path("email").asText());
        assertEquals("[REDACTED]", record.path("recordId").asText());
        assertEquals("[REDACTED]", record.path("message").asText());
        assertEquals("[REDACTED]", record.path("requestBody").asText());
        assertFalse(json.contains("person@example.org"));
        assertFalse(json.contains("private-record-id"));
        assertFalse(json.contains("private submission text"));
        assertFalse(json.contains("private request body"));
        assertFalse(json.contains("must not be logged"));
        assertTrue(record.has("timestamp"));
        assertTrue(record.has("level"));
    }
}
