package com.saved2serve.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OperationalLog {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> SAFE_FIELDS = Set.of(
            "service", "outcome", "statusCode", "durationMs", "messageCount",
            "successCount", "failureCount", "errorType", "requestId"
    );
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "address", "authorization", "body", "content", "email", "emailaddress",
            "engagementid", "message", "name", "password", "phone", "phonenumber", "recordid",
            "recipient", "secret", "token", "userid"
    );
    private static final Logger LOGGER = Logger.getLogger(OperationalLog.class.getName());

    static {
        LOGGER.setUseParentHandlers(false);
        LOGGER.addHandler(new java.util.logging.StreamHandler(System.out, new java.util.logging.Formatter() {
            @Override
            public String format(java.util.logging.LogRecord record) {
                return record.getMessage() + System.lineSeparator();
            }
        }) {
            @Override
            public synchronized void publish(java.util.logging.LogRecord record) {
                super.publish(record);
                flush();
            }
        });
    }

    private OperationalLog() {
    }

    public static void info(String event, Map<String, ?> fields) {
        log(Level.INFO, event, fields);
    }

    public static void warning(String event, Map<String, ?> fields) {
        log(Level.WARNING, event, fields);
    }

    public static void error(String event, Map<String, ?> fields) {
        log(Level.SEVERE, event, fields);
    }

    private static void log(Level level, String event, Map<String, ?> fields) {
        LOGGER.log(level, format(level, event, fields));
    }

    static String format(Level level, String event, Map<String, ?> fields) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", Instant.now().toString());
        record.put("level", level.getName());
        record.put("event", event);
        if (fields != null) {
            fields.forEach((key, value) -> {
                if (key == null || value == null) {
                    return;
                }
                String normalizedKey = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                if (isSensitive(normalizedKey)) {
                    record.put(key, "[REDACTED]");
                } else if (SAFE_FIELDS.contains(key)
                        && (value instanceof String || value instanceof Number || value instanceof Boolean)) {
                    record.put(key, value);
                }
            });
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(record);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("Operational log record could not be serialized.", serializationFailure);
        }
    }

    private static boolean isSensitive(String normalizedKey) {
        return SENSITIVE_FIELDS.stream().anyMatch(sensitiveField ->
                normalizedKey.equals(sensitiveField) || normalizedKey.endsWith(sensitiveField)
        );
    }
}
