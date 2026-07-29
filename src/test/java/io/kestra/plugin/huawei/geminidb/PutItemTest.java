package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    // GeminiDB's DynamoDB-compatible data plane authenticates against the instance's own database
    // account (`rwuser` + admin password) and never consults IAM, so an STS security token has
    // nothing to authenticate against. dynamodb-local accepts any credential and would happily let
    // this through, which is exactly why the rejection has to be asserted here rather than trusted
    // to surface at runtime — on real GeminiDB it comes back as an undiagnosable
    // `AccessDeniedException: auth failed`.
    @Test
    void putItem_withSecurityToken_isRejectedUpFront() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = applyGeminiDbConfig(PutItem.builder())
            .securityToken(Property.ofValue("dummy-session-token"))
            .item(Property.ofValue(Map.of("id", IdUtils.create(), "firstname", "Jane")))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("does not support 'securityToken'"));
        assertThat(exception.getMessage(), containsString("rwuser"));
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

    @Test
    void putItem_listWithDuplicates_roundTripsAsListPreservingOrderAndDuplicates() throws Exception {
        var id = IdUtils.create();
        var runContext = runContextFactory.of(Collections.emptyMap());
        var tags = List.of("z", "a", "z");

        var task = applyGeminiDbConfig(PutItem.builder())
            .item(Property.ofValue(Map.of("id", id, "tags", tags, "nested", Map.of("labels", tags))))
            .build();

        var putOutput = task.run(runContext);
        assertThat(putOutput, nullValue());

        var getTask = applyGeminiDbConfig(GetItem.builder())
            .key(Property.ofValue(Map.of("id", id)))
            .build();

        var getOutput = getTask.run(runContext);

        assertThat(getOutput.getRow().get("tags"), equalTo(tags));
        assertThat(((Map<?, ?>) getOutput.getRow().get("nested")).get("labels"), equalTo(tags));
    }
}
