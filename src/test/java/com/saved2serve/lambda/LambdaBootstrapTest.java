package com.saved2serve.lambda;

import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class LambdaBootstrapTest {

    @Test
    void buildWithoutAwsCredentials() {
        String originalAccessKey = System.getProperty("aws.accessKeyId");
        String originalSecretKey = System.getProperty("aws.secretAccessKey");
        String originalSessionToken = System.getProperty("aws.sessionToken");

        try {
            System.clearProperty("aws.accessKeyId");
            System.clearProperty("aws.secretAccessKey");
            System.clearProperty("aws.sessionToken");

            Supplier<String> supplier = () -> "ready";
            LambdaBootstrap<String> bootstrap = LambdaBootstrap.create(supplier);

            assertNotNull(bootstrap);
            assertEquals("ready", bootstrap.get());
        } finally {
            restoreProperty("aws.accessKeyId", originalAccessKey);
            restoreProperty("aws.secretAccessKey", originalSecretKey);
            restoreProperty("aws.sessionToken", originalSessionToken);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, value);
    }
}
