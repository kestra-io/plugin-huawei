package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fail-fast validation tests that don't require a running DynamoDB Local instance: the connection
 * layer rejects a missing/invalid {@code endpoint} or incomplete AK/SK before any client is built.
 */
@KestraTest
class GeminiDbValidationTest {

    @Inject
    RunContextFactory runContextFactory;

    @Inject
    ModelValidator modelValidator;

    @Test
    void queryTaskValidates_noHibernateValidatorError() {
        var task = Query.builder()
            .accessKeyId(Property.ofValue("ak"))
            .secretAccessKey(Property.ofValue("sk"))
            .endpoint(Property.ofValue("http://localhost:8000"))
            .tableName(Property.ofValue("table"))
            .keyConditionExpression(Property.ofValue("id = :id"))
            .expressionAttributeValues(Property.ofValue(Map.of(":id", "1")))
            .limit(Property.ofValue(100))
            .build();

        assertDoesNotThrow(() -> modelValidator.isValid(task));
    }

    @Test
    void scanTaskValidates_noHibernateValidatorError() {
        var task = Scan.builder()
            .accessKeyId(Property.ofValue("ak"))
            .secretAccessKey(Property.ofValue("sk"))
            .endpoint(Property.ofValue("http://localhost:8000"))
            .tableName(Property.ofValue("table"))
            .limit(Property.ofValue(100))
            .build();

        assertDoesNotThrow(() -> modelValidator.isValid(task));
    }

    @Test
    void limitOutOfRange_failsFast() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = Query.builder()
            .accessKeyId(Property.ofValue("ak"))
            .secretAccessKey(Property.ofValue("sk"))
            .endpoint(Property.ofValue("http://localhost:8000"))
            .tableName(Property.ofValue("table"))
            .keyConditionExpression(Property.ofValue("id = :id"))
            .expressionAttributeValues(Property.ofValue(Map.of(":id", "1")))
            .limit(Property.ofValue(2000))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("limit"));
    }

    @Test
    void missingEndpoint_failsFast() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = PutItem.builder()
            .accessKeyId(Property.ofValue("ak"))
            .secretAccessKey(Property.ofValue("sk"))
            .tableName(Property.ofValue("table"))
            .item(Property.ofValue(Map.of("id", "1")))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("endpoint"));
    }

    @Test
    void invalidEndpointUri_failsFast() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = PutItem.builder()
            .accessKeyId(Property.ofValue("ak"))
            .secretAccessKey(Property.ofValue("sk"))
            .endpoint(Property.ofValue("://not-a-valid-uri"))
            .tableName(Property.ofValue("table"))
            .item(Property.ofValue(Map.of("id", "1")))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("endpoint"));
    }

    @Test
    void missingCredentials_failsFast() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = PutItem.builder()
            .endpoint(Property.ofValue("http://localhost:8000"))
            .tableName(Property.ofValue("table"))
            .item(Property.ofValue(Map.of("id", "1")))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("AK/SK"));
    }
}
