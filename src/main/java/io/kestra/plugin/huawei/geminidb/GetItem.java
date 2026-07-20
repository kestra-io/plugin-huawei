package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get a GeminiDB (DynamoDB-compatible) item by key",
    description = "Retrieves a single item by its primary key. Returns an empty `row` when the item " +
        "does not exist."
)
@Plugin(
    examples = {
        @Example(
            title = "Get an item by its key",
            full = true,
            code = """
                id: geminidb_get_item
                namespace: company.team

                tasks:
                  - id: get_item
                    type: io.kestra.plugin.huawei.geminidb.GetItem
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
public class GetItem extends AbstractGeminiDb implements RunnableTask<GetItem.Output> {

    @Schema(
        title = "Item key",
        description = "Full primary key map (partition key, plus sort key when the table defines one)."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> key;

    @Override
    public Output run(RunContext runContext) throws Exception {
        try (var dynamoDb = client(runContext)) {
            var renderedKey = runContext.render(this.key).asMap(String.class, Object.class);

            var getRequest = GetItemRequest.builder()
                .tableName(renderedTableName(runContext))
                .key(valueMapFrom(renderedKey))
                .build();

            var response = dynamoDb.getItem(getRequest);
            var row = objectMapFrom(response.item());

            return Output.builder().row(row).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Item",
            description = "The fetched item as a map; empty when no item matches the key."
        )
        private Map<String, Object> row;
    }
}
