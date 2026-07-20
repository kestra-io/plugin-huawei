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
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;

import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Delete a GeminiDB (DynamoDB-compatible) item by key",
    description = "Deletes a single item using the provided primary key; no condition expression is " +
        "applied."
)
@Plugin(
    examples = {
        @Example(
            title = "Delete an item by its key",
            full = true,
            code = """
                id: geminidb_delete_item
                namespace: company.team

                tasks:
                  - id: delete_item
                    type: io.kestra.plugin.huawei.geminidb.DeleteItem
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    endpoint: "https://192.168.0.10:8635"
                    tableName: persons
                    key:
                      id: "1"
                """
        )
    }
)
public class DeleteItem extends AbstractGeminiDb implements RunnableTask<VoidOutput> {

    @Schema(
        title = "Item key",
        description = "Full primary key map (partition key, plus sort key when the table defines one)."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> key;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        try (var dynamoDb = client(runContext)) {
            var rKey = runContext.render(this.key).asMap(String.class, Object.class);

            var deleteRequest = DeleteItemRequest.builder()
                .tableName(renderedTableName(runContext))
                .key(valueMapFrom(rKey))
                .build();

            dynamoDb.deleteItem(deleteRequest);
            return null;
        }
    }
}
