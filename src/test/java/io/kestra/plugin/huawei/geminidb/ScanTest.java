package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.utils.IdUtils;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Collections;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScanTest extends AbstractGeminiDbTest {

    @Test
    void scan_happyPath_returnsAllItems() throws Exception {
        rawClient.putItem(builder -> builder
            .tableName(testTableName)
            .item(Map.of("id", AttributeValue.fromS(IdUtils.create()), "kind", AttributeValue.fromS("a"))));
        rawClient.putItem(builder -> builder
            .tableName(testTableName)
            .item(Map.of("id", AttributeValue.fromS(IdUtils.create()), "kind", AttributeValue.fromS("b"))));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyGeminiDbConfig(Scan.builder())
            .fetchType(Property.ofValue(FetchType.FETCH))
            .limit(Property.ofValue(1000))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRows().size(), greaterThanOrEqualTo(2));
    }

    @Test
    void scan_withFilterExpression_narrowsResults() throws Exception {
        var id = IdUtils.create();
        var uniqueKind = "kind-" + id;
        rawClient.putItem(builder -> builder
            .tableName(testTableName)
            .item(Map.of("id", AttributeValue.fromS(id), "kind", AttributeValue.fromS(uniqueKind))));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyGeminiDbConfig(Scan.builder())
            .filterExpression(Property.ofValue("kind = :kind"))
            .expressionAttributeValues(Property.ofValue(Map.of(":kind", uniqueKind)))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRows(), hasSize(1));
    }

    @Test
    void scan_filterExpressionWithoutAttributeValues_throws() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyGeminiDbConfig(Scan.builder())
            .filterExpression(Property.ofValue("kind = :kind"))
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }
}
