package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Collections;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class DeleteItemTest extends AbstractGeminiDbTest {

    @Test
    void deleteItem_happyPath_itemIsRemoved() throws Exception {
        var id = IdUtils.create();
        rawClient.putItem(builder -> builder
            .tableName(testTableName)
            .item(Map.of("id", AttributeValue.fromS(id))));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyGeminiDbConfig(DeleteItem.builder())
            .key(Property.ofValue(Map.of("id", id)))
            .build();

        var output = task.run(runContext);
        assertThat(output, nullValue());

        var response = rawClient.getItem(builder -> builder
            .tableName(testTableName)
            .key(Map.of("id", AttributeValue.fromS(id))));

        assertThat(response.hasItem(), is(false));
    }

    @Test
    void deleteItem_missingKey_succeedsSilently() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyGeminiDbConfig(DeleteItem.builder())
            .key(Property.ofValue(Map.of("id", IdUtils.create())))
            .build();

        assertThat(task.run(runContext), nullValue());
    }
}
