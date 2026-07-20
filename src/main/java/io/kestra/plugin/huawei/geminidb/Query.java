package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Query items from a GeminiDB (DynamoDB-compatible) table",
    description = """
        Executes a Query request using a key condition expression — the Huawei Cloud GeminiDB for
        NoSQL equivalent of `io.kestra.plugin.aws.dynamodb.Query`. `fetchType` defaults to `STORE`,
        writing matching rows to internal storage; `FETCH`/`FETCH_ONE` load them into memory; `NONE`
        runs the query without fetching any results.

        Reads a single response page: items beyond `limit` (or GeminiDB's own page-size limit) are
        not automatically paginated across `LastEvaluatedKey`. A log message is emitted whenever the
        response was truncated so a partial result set isn't missed silently.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Query items by partition key",
            full = true,
            code = """
                id: geminidb_query
                namespace: company.team

                tasks:
                  - id: query
                    type: io.kestra.plugin.huawei.geminidb.Query
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    endpoint: "https://192.168.0.10:8635"
                    tableName: persons
                    keyConditionExpression: "id = :id"
                    expressionAttributeValues:
                      ":id": "1"
                """
        ),
        @Example(
            title = "Query items with an additional filter expression",
            full = true,
            code = """
                id: geminidb_query_filter
                namespace: company.team

                tasks:
                  - id: query
                    type: io.kestra.plugin.huawei.geminidb.Query
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    endpoint: "https://192.168.0.10:8635"
                    tableName: persons
                    keyConditionExpression: "id = :id"
                    filterExpression: "lastname = :lastname"
                    expressionAttributeValues:
                      ":id": "1"
                      ":lastname": "Doe"
                """
        )
    },
    metrics = {
        @Metric(
            name = "records",
            type = Counter.TYPE,
            unit = "items",
            description = "Number of items fetched from the GeminiDB query."
        )
    }
)
public class Query extends AbstractGeminiDb implements RunnableTask<FetchOutput> {

    @Schema(
        title = "Key condition expression",
        description = "Expression on the table's partition key (and sort key, if defined), e.g. `id = :id`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> keyConditionExpression;

    @Schema(
        title = "Expression attribute values",
        description = "Map of `:placeholder` values referenced by `keyConditionExpression` and, if set, `filterExpression`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> expressionAttributeValues;

    @Schema(
        title = "Filter expression",
        description = "Additional server-side filter evaluated after the key condition; its placeholders must also be present in `expressionAttributeValues`."
    )
    @PluginProperty(group = "processing")
    private Property<String> filterExpression;

    @Schema(
        title = "Maximum number of items to evaluate",
        description = "Caps the number of items read in the single response page (1-1000). Defaults " +
            "to 100. Does not paginate across `LastEvaluatedKey` — see the task description."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> limit = Property.ofValue(100);

    @Schema(
        title = "Fetch strategy",
        description = "`STORE` (default) writes matching rows to internal storage; `FETCH` loads all " +
            "rows into memory; `FETCH_ONE` returns only the first row; `NONE` runs the query without " +
            "fetching results."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        int rLimit = renderedLimit(runContext, this.limit);
        try (var dynamoDb = client(runContext)) {
            var queryBuilder = QueryRequest.builder()
                .tableName(renderedTableName(runContext))
                .keyConditionExpression(runContext.render(this.keyConditionExpression).as(String.class)
                    .orElseThrow(() -> new IllegalArgumentException("'keyConditionExpression' must be set")))
                .expressionAttributeValues(valueMapFrom(runContext.render(this.expressionAttributeValues).asMap(String.class, Object.class)))
                .limit(rLimit);

            var rFilterExpression = runContext.render(this.filterExpression).as(String.class).orElse(null);
            if (rFilterExpression != null) {
                queryBuilder.filterExpression(rFilterExpression);
            }

            var response = dynamoDb.query(queryBuilder.build());

            this.warnIfTruncated(runContext, response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty(), "query");

            return this.fetchOutputs(response.items(), runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.STORE), runContext);
        }
    }
}
