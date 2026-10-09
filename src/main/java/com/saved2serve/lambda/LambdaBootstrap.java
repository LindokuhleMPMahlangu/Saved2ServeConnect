package com.saved2serve.lambda;

import java.util.Objects;
import java.util.function.Supplier;

public final class LambdaBootstrap<T> implements Supplier<T> {
    private final Supplier<T> delegate;

    private LambdaBootstrap(Supplier<T> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    public static <T> LambdaBootstrap<T> create(Supplier<T> delegate) {
        return new LambdaBootstrap<>(delegate);
    }

    @Override
    public T get() {
        return delegate.get();
    }

    public static void configureEnvironmentDefaults() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
        System.clearProperty("aws.sessionToken");
    }
}
