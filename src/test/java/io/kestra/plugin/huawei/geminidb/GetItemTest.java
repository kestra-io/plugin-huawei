package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Collections;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;

class GetItemTest extends AbstractGeminiDbTest {

    @Test
    void getItem_happyPath_returnsRow() throws Exception {
        var id = IdUtils.create();
        rawClient.putItem(builder -> builder
            .tableName(testTableName)
            .item(Map.of("id", AttributeValue.fromS(id), "firstname", AttributeValue.fromS("Jane"))));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyGeminiDbConfig(GetItem.builder())
            .key(Property.ofValue(Map.of("id", id)))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRow().get("firstname"), equalTo("Jane"));
    }

    @Test
    void getItem_missingKey_returnsEmptyRow() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyGeminiDbConfig(GetItem.builder())
            .key(Property.ofValue(Map.of("id", IdUtils.create())))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRow(), anEmptyMap());
    }
}
