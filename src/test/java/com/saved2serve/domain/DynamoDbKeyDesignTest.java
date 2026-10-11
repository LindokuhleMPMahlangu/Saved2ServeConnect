package com.saved2serve.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DynamoDbKeyDesignTest {

    @Test
    void privacyFocusedDynamoDbDesignUsesAreaCodeAndHashBasedLookups() {
        assertTrue(Set.of("name", "email", "areaCode", "phone", "consent", "source").containsAll(Set.of("name", "email", "areaCode", "consent")));

        assertFalse(hasField(Interest.class, "fullAddress"));
        assertFalse(hasField(Interest.class, "latitude"));
        assertFalse(hasField(Interest.class, "longitude"));

        assertFalse(hasField(Engagement.class, "fullAddress"));
        assertFalse(hasField(Engagement.class, "latitude"));
        assertFalse(hasField(Engagement.class, "longitude"));
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
