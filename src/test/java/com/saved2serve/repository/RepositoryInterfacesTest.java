package com.saved2serve.repository;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryInterfacesTest {

    @Test
    void repositoryContractsUseNoAwsSdkTypes() {
        assertTrue(InterestRepository.class.isInterface());
        assertTrue(EngagementRepository.class.isInterface());
        assertTrue(ChurchDirectoryRepository.class.isInterface());

        Stream.of(InterestRepository.class, EngagementRepository.class, ChurchDirectoryRepository.class)
                .flatMap(repository -> Arrays.stream(repository.getDeclaredMethods()))
                .forEach(RepositoryInterfacesTest::assertNoAwsSdkTypes);
    }

    private static void assertNoAwsSdkTypes(Method method) {
        assertNoAwsSdkType(method.getGenericReturnType().getTypeName());
        Arrays.stream(method.getGenericParameterTypes())
                .forEach(type -> assertNoAwsSdkType(type.getTypeName()));
    }

    private static void assertNoAwsSdkType(String typeName) {
        assertFalse(typeName.contains("com.amazonaws."));
        assertFalse(typeName.contains("software.amazon.awssdk."));
    }
}
