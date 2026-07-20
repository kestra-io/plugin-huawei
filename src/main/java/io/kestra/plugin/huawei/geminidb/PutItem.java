package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Put an item into a GeminiDB (DynamoDB-compatible) table",
    description = "Creates or replaces an item — an upsert when the key already exists."
)
@Plugin(
    examples = {
        @Example(
            title = "Put an item into a GeminiDB table",
            full = true,
            code = """
                id: geminidb_put_item
                namespace: company.team

                tasks:
                  - id: put_item
                    type: io.kestra.plugin.huawei.geminidb.PutItem
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    endpoint: "https://192.168.0.10:8635"
                    tableName: persons
                    item:
                      id: "1"
                      firstname: John
                      lastname: Doe
                """
        )
    }
)
public class PutItem extends AbstractGeminiDb implements RunnableTask<VoidOutput> {

    @Schema(
        title = "Item",
        description = "Full item content as a map, including its primary key attributes. Numeric " +
            "values are stored as DynamoDB string (`S`) attributes, not numbers (`N`) — quote or " +
            "compare them as strings in downstream expressions."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> item;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        try (var dynamoDb = client(runContext)) {
            var fields = runContext.render(this.item).asMap(String.class, Object.class);

            var putRequest = PutItemRequest.builder()
                .tableName(renderedTableName(runContext))
                .item(valueMapFrom(fields))
                .build();

            dynamoDb.putItem(putRequest);
            return null;
        }
    }
}
