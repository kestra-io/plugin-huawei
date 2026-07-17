package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.utils.IdUtils;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Collections;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

class QueryTest extends AbstractGeminiDbTest {

    @Test
    @SuppressWarnings("unchecked")
    void query_happyPath_returnsMatchingItem() throws Exception {
        var id = IdUtils.create();
        rawClient.putItem(builder -> builder
            .tableName(testTableName)
            .item(Map.of("id", AttributeValue.fromS(id), "lastname", AttributeValue.fromS("Doe"))));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyGeminiDbConfig(Query.builder())
            .keyConditionExpression(Property.ofValue("id = :id"))
            .expressionAttributeValues(Property.ofValue(Map.of(":id", id)))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRows(), hasSize(1));
        var row = (Map<String, Object>) output.getRows().getFirst();
        assertThat(row.get("lastname"), equalTo("Doe"));
    }

    @Test
    void query_store_writesToInternalStorage() throws Exception {
        var id = IdUtils.create();
        rawClient.putItem(builder -> builder
            .tableName(testTableName)
            .item(Map.of("id", AttributeValue.fromS(id))));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyGeminiDbConfig(Query.builder())
            .keyConditionExpression(Property.ofValue("id = :id"))
            .expressionAttributeValues(Property.ofValue(Map.of(":id", id)))
            .build();

        var output = task.run(runContext);

        assertThat(output.getUri(), notNullValue());
        assertThat(output.getSize(), equalTo(1L));
    }

    @Test
    void query_noMatch_returnsEmpty() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyGeminiDbConfig(Query.builder())
            .keyConditionExpression(Property.ofValue("id = :id"))
            .expressionAttributeValues(Property.ofValue(Map.of(":id", IdUtils.create())))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRows(), empty());
    }
}
