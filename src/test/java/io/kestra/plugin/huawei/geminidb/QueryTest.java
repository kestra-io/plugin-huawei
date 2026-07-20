package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.utils.IdUtils;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.util.Collections;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

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

    @Test
    void query_fetchOne_returnsSingleRowOnly() throws Exception {
        var id = IdUtils.create();
        rawClient.putItem(builder -> builder
            .tableName(testTableName)
            .item(Map.of("id", AttributeValue.fromS(id), "lastname", AttributeValue.fromS("Doe"))));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyGeminiDbConfig(Query.builder())
            .keyConditionExpression(Property.ofValue("id = :id"))
            .expressionAttributeValues(Property.ofValue(Map.of(":id", id)))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRow(), equalTo(Map.of("id", id, "lastname", "Doe")));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getUri(), nullValue());
    }

    @Test
    void query_fetchTypeNone_returnsNoRows() throws Exception {
        var id = IdUtils.create();
        rawClient.putItem(builder -> builder
            .tableName(testTableName)
            .item(Map.of("id", AttributeValue.fromS(id))));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyGeminiDbConfig(Query.builder())
            .keyConditionExpression(Property.ofValue("id = :id"))
            .expressionAttributeValues(Property.ofValue(Map.of(":id", id)))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRows(), nullValue());
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), nullValue());
        assertThat(output.getSize(), equalTo(0L));
    }

    @Test
    void query_truncatedByLimit_returnsBoundedResult() throws Exception {
        var truncationTable = "kestra_geminidb_querytest_truncation_" + IdUtils.create();
        rawClient.createTable(builder -> builder
            .tableName(truncationTable)
            .keySchema(
                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
            .attributeDefinitions(
                AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
            .billingMode(BillingMode.PAY_PER_REQUEST));

        try {
            var pk = IdUtils.create();
            for (int i = 0; i < 5; i++) {
                rawClient.putItem(builder -> builder
                    .tableName(truncationTable)
                    .item(Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS(IdUtils.create()))));
            }

            var runContext = runContextFactory.of(Collections.emptyMap());
            var task = applyGeminiDbConfig(Query.builder())
                .tableName(Property.ofValue(truncationTable))
                .keyConditionExpression(Property.ofValue("pk = :pk"))
                .expressionAttributeValues(Property.ofValue(Map.of(":pk", pk)))
                .fetchType(Property.ofValue(FetchType.FETCH))
                .limit(Property.ofValue(2))
                .build();

            var output = task.run(runContext);

            assertThat(output.getRows(), hasSize(2));
        } finally {
            rawClient.deleteTable(builder -> builder.tableName(truncationTable));
        }
    }
}
