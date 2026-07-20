package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Collections;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

class PutItemTest extends AbstractGeminiDbTest {

    @Test
    void putItem_happyPath_itemIsWritten() throws Exception {
        var id = IdUtils.create();
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = applyGeminiDbConfig(PutItem.builder())
            .item(Property.ofValue(Map.of("id", id, "firstname", "John", "lastname", "Doe")))
            .build();

        var output = task.run(runContext);
        assertThat(output, nullValue());

        var response = rawClient.getItem(builder -> builder
            .tableName(testTableName)
            .key(Map.of("id", AttributeValue.fromS(id))));

        assertThat(response.item().get("firstname").s(), equalTo("John"));
        assertThat(response.item().get("lastname").s(), equalTo("Doe"));
    }

    @Test
    void putItem_withSecurityToken_usesSessionCredentials() throws Exception {
        var id = IdUtils.create();
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = applyGeminiDbConfig(PutItem.builder())
            .securityToken(Property.ofValue("dummy-session-token"))
            .item(Property.ofValue(Map.of("id", id, "firstname", "Jane")))
            .build();

        var output = task.run(runContext);
        assertThat(output, nullValue());

        var response = rawClient.getItem(builder -> builder
            .tableName(testTableName)
            .key(Map.of("id", AttributeValue.fromS(id))));

        assertThat(response.item().get("firstname").s(), equalTo("Jane"));
    }

    @Test
    void putItem_existingKey_upsertsInPlace() throws Exception {
        var id = IdUtils.create();
        var runContext = runContextFactory.of(Collections.emptyMap());

        applyGeminiDbConfig(PutItem.builder())
            .item(Property.ofValue(Map.of("id", id, "status", "pending")))
            .build()
            .run(runContext);

        applyGeminiDbConfig(PutItem.builder())
            .item(Property.ofValue(Map.of("id", id, "status", "done")))
            .build()
            .run(runContext);

        var response = rawClient.getItem(builder -> builder
            .tableName(testTableName)
            .key(Map.of("id", AttributeValue.fromS(id))));

        assertThat(response.item().get("status").s(), equalTo("done"));
    }
}
