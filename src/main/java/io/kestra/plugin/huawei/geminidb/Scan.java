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
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Scan a GeminiDB (DynamoDB-compatible) table",
    description = """
        Performs a Scan over the entire table. `fetchType` defaults to `STORE`, writing matching rows
        to internal storage; `FETCH`/`FETCH_ONE` load them into memory; `NONE` runs the scan without
        fetching any results. An optional `filterExpression` narrows the returned set after items are
        read, so it does not reduce the read cost.

        Reads a single response page: items beyond `limit` (or GeminiDB's own page-size limit) are
        not automatically paginated across `LastEvaluatedKey`. A log message is emitted whenever the
        response was truncated so a partial result set isn't missed silently.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Scan an entire table",
            full = true,
            code = """
                id: geminidb_scan
                namespace: company.team

                tasks:
                  - id: scan
                    type: io.kestra.plugin.huawei.geminidb.Scan
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    endpoint: "https://192.168.0.10:8635"
                    tableName: persons
                """
        ),
        @Example(
            title = "Scan a table with a filter expression",
            full = true,
            code = """
                id: geminidb_scan_filter
                namespace: company.team

                tasks:
                  - id: scan
                    type: io.kestra.plugin.huawei.geminidb.Scan
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    endpoint: "https://192.168.0.10:8635"
                    tableName: persons
                    filterExpression: "lastname = :lastname"
                    expressionAttributeValues:
                      ":lastname": "Doe"
                """
        )
    },
    metrics = {
        @Metric(
            name = "records",
            type = Counter.TYPE,
            unit = "items",
            description = "Number of items scanned from GeminiDB."
        )
    }
)
public class Scan extends AbstractGeminiDb implements RunnableTask<FetchOutput> {

    @Schema(
        title = "Filter expression",
        description = "Server-side filter applied to scanned items; requires `expressionAttributeValues`."
    )
    @PluginProperty(group = "processing")
    private Property<String> filterExpression;

    @Schema(
        title = "Expression attribute values",
        description = """
            Map of `:placeholder` values referenced by `filterExpression`. Numeric values are stored
            as DynamoDB string (`S`) attributes, not numbers (`N`) — quote or compare them as strings.
            """
    )
    @PluginProperty(group = "processing")
    private Property<Map<String, Object>> expressionAttributeValues;

    @Schema(
        title = "Maximum number of items to evaluate",
        description = """
            Caps the number of items read in the single response page (1-1000). Defaults to 100.
            Does not paginate across `LastEvaluatedKey` — see the task description.
            """
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> limit = Property.ofValue(100);

    @Schema(
        title = "Fetch strategy",
        description = """
            `STORE` (default) writes matching rows to internal storage; `FETCH` loads all rows into
            memory; `FETCH_ONE` returns only the first row; `NONE` runs the scan without fetching
            results.
            """
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        int rLimit = renderedLimit(runContext, this.limit);
        try (var dynamoDb = client(runContext)) {
            var scanBuilder = ScanRequest.builder()
                .tableName(renderedTableName(runContext))
                .limit(rLimit);

            var rFilterExpression = runContext.render(this.filterExpression).as(String.class).orElse(null);
            if (rFilterExpression != null) {
                var rExpressionAttributeValues = runContext.render(this.expressionAttributeValues).asMap(String.class, Object.class);
                if (rExpressionAttributeValues.isEmpty()) {
                    throw new IllegalArgumentException("'expressionAttributeValues' must be provided when 'filterExpression' is set");
                }
                scanBuilder.filterExpression(rFilterExpression).expressionAttributeValues(valueMapFrom(rExpressionAttributeValues));
            }

            var response = dynamoDb.scan(scanBuilder.build());

            this.warnIfTruncated(runContext, response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty(), "scan");

            return this.fetchOutputs(response.items(), runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.STORE), runContext);
        }
    }
}
