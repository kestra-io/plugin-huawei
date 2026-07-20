package io.kestra.plugin.huawei.dli;

import com.huaweicloud.sdk.dli.v1.DliClient;
import com.huaweicloud.sdk.dli.v1.model.CreateSqlJobResponse;
import com.huaweicloud.sdk.dli.v1.model.PreviewSqlJobResultResponse;
import com.huaweicloud.sdk.dli.v1.model.ShowSqlJobStatusResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.executions.metrics.Timer;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.huawei.obs.AuthType;
import io.kestra.plugin.huawei.obs.ObsService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a SQL query on Huawei Cloud DLI (Data Lake Insight)",
    description = """
        Submits a SQL statement as a DLI SQL job, waits for it to reach a terminal state, and returns
        its results — the Huawei Cloud equivalent of `io.kestra.plugin.aws.athena.Query`.

        DLI queries data in OBS or federated sources (RDS, DWS, CSS, and more) via a serverless
        Spark SQL engine. Results are handled according to `fetchType`:

        - `STORE` (default): the full result set is exported to `outputLocation` on OBS, then
          downloaded and re-serialized as ION into Kestra internal storage. Use this for result sets
          that may exceed 1000 rows.
        - `FETCH` / `FETCH_ONE`: results are read directly from DLI's result preview API, which is
          capped at 1000 rows. Not supported on DLI's shared `default` queue — a dedicated DLI SQL
          queue is required for these fetch types.
        - `NONE`: the task returns as soon as the job reaches a terminal state, without fetching rows.
          Use this for DDL/DML statements that don't return a result set.

        Authentication uses AK/SK request signing. Provide `accessKeyId` and `secretAccessKey` via
        `{{ secret('NAME') }}`, or configure `temporaryCredentials` for inline IAM credential exchange.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Run a query and store the full result set on OBS.",
            full = true,
            code = """
                id: dli_query_store
                namespace: company.team

                tasks:
                  - id: query
                    type: io.kestra.plugin.huawei.dli.Query
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    sql: "SELECT * FROM my_database.my_table WHERE event_date = '2024-01-01'"
                    database: my_database
                    queue: my_queue
                    outputLocation: "obs://my-bucket/dli-results/"
                """
        ),
        @Example(
            title = "Run an aggregate query and fetch a single row directly.",
            full = true,
            code = """
                id: dli_query_fetch_one
                namespace: company.team

                tasks:
                  - id: query
                    type: io.kestra.plugin.huawei.dli.Query
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    sql: "SELECT COUNT(*) AS total FROM my_database.my_table"
                    database: my_database
                    queue: my_queue
                    fetchType: FETCH_ONE
                """
        ),
        @Example(
            title = "Fire-and-forget a DDL statement without waiting for a result set.",
            full = true,
            code = """
                id: dli_query_ddl
                namespace: company.team

                tasks:
                  - id: create_table
                    type: io.kestra.plugin.huawei.dli.Query
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    sql: "CREATE TABLE IF NOT EXISTS my_database.my_table (id INT, name STRING)"
                    database: my_database
                    queue: my_queue
                    fetchType: NONE
                """
        ),
        @Example(
            title = "Run a query against the Huawei Cloud European sovereign cloud.",
            full = true,
            code = """
                id: dli_query_eu_sovereign
                namespace: company.team

                tasks:
                  - id: query
                    type: io.kestra.plugin.huawei.dli.Query
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    endpointSuffix: myhuaweicloud.eu
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    sql: "SELECT * FROM my_database.my_table LIMIT 100"
                    database: my_database
                    queue: my_queue
                    fetchType: FETCH
                """
        )
    },
    metrics = {
        @Metric(name = "dli.query.rows", type = Counter.TYPE, unit = "rows",
            description = "Number of rows fetched or stored from the DLI query result."),
        @Metric(name = "dli.query.duration", type = Timer.TYPE,
            description = "DLI-reported job execution duration, when available.")
    }
)
public class Query extends AbstractDli implements RunnableTask<Query.Output> {

    // DLI's previewSqlJobResult API is hard-capped at 1000 rows and does not paginate.
    static final int PREVIEW_ROW_CAP = 1000;

    @Schema(title = "SQL statement to run", description = "Any DLI-supported SQL: SELECT, DDL, DML, and more.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> sql;

    @Schema(title = "Database to run the query against", description = "Maps to DLI's `currentdb`. Optional if the query fully qualifies its table names.")
    @PluginProperty(group = "main")
    private Property<String> database;

    @Schema(
        title = "DLI queue to run the job on",
        description = """
            Maps to DLI's `queue_name`. When omitted, the account's shared `default` queue is used.
            The `default` queue does not support fetching results via the preview API: `fetchType`
            `FETCH`/`FETCH_ONE` on the `default` queue (or with `queue` omitted) fails fast. Use
            `fetchType` `STORE` or `NONE` on the `default` queue, or run the query on a dedicated
            DLI SQL queue to use `FETCH`/`FETCH_ONE`.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> queue;

    @Schema(
        title = "How to handle the query result set",
        description = """
            `STORE` (default) exports the full result to `outputLocation` on OBS and downloads it as
            ION. `FETCH` and `FETCH_ONE` read directly from DLI's preview API (capped at 1000 rows) and
            are not supported on the shared `default` queue (see `queue`). `NONE` returns as soon as
            the job completes, without fetching a result set.
            """
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Schema(
        title = "OBS location the result set is exported to",
        description = "An `obs://bucket/prefix` URI. Required when `fetchType` is `STORE`."
    )
    @PluginProperty(group = "destination")
    private Property<String> outputLocation;

    @Schema(title = "Extra Spark/DLI configuration entries", description = "Each entry is a `key=value` string, forwarded as-is to DLI's `conf` field.")
    @PluginProperty(group = "advanced")
    private Property<List<String>> conf;

    @Schema(title = "Tags to attach to the DLI job")
    @PluginProperty(group = "advanced")
    private Property<Map<String, String>> tags;

    @Schema(
        title = "Maximum time to wait for the query (and, for `STORE`, the export job) to complete",
        description = "ISO-8601 duration (e.g. `PT1H`). Defaults to 1 hour. The DLI job is not automatically cancelled on timeout."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration = Property.ofValue(Duration.ofHours(1));

    @Schema(title = "Polling interval while waiting for the job to complete", description = "ISO-8601 duration (e.g. `PT5S`). Defaults to 5 seconds.")
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> interval = Property.ofValue(Duration.ofSeconds(5));

    @Schema(
        title = "OBS endpoint URL override for the `STORE` result read-back",
        description = """
            Overrides the endpoint used to read the exported result back from OBS. This is a
            separate host from the DLI `endpointOverride` — never reuse the DLI endpoint here.
            Defaults to the OBS endpoint derived from `region` and `endpointSuffix`.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> obsEndpointOverride;

    @Schema(title = "Use path-style access for the OBS read-back", description = "Set to `true` for MinIO or other S3-compatible endpoints. Defaults to `false`.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> obsPathStyleAccess = Property.ofValue(false);

    @Schema(title = "OBS request-signing type for the read-back", description = "Defaults to `OBS`. Set to `V2` for MinIO or other S3-compatible endpoints.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<AuthType> obsAuthType = Property.ofValue(AuthType.OBS);

    // Guards against a duplicate/racing kill signal; cancels the remote DLI job so it stops consuming
    // queue resources after the Kestra execution is killed. Excluded from equals/hashCode/toString:
    // AtomicReference/AtomicBoolean use identity equality, so two otherwise-identical task instances
    // would never be equal, and their content is a runtime implementation detail, not task config.
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final AtomicReference<Runnable> killable = new AtomicReference<>();

    @Builder.Default
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final AtomicBoolean isKilled = new AtomicBoolean(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rSql = runContext.render(sql).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("sql is required"));
        var rDatabase = runContext.render(database).as(String.class).orElse(null);
        var rQueue = runContext.render(queue).as(String.class).orElse(null);
        var rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.STORE);
        var rOutputLocation = runContext.render(outputLocation).as(String.class).orElse(null);
        var rConf = runContext.render(conf).asList(String.class);
        var rTags = runContext.render(tags).asMap(String.class, String.class);
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(Duration.ofHours(1));
        var rInterval = runContext.render(interval).as(Duration.class).orElse(Duration.ofSeconds(5));

        if (rFetchType == FetchType.STORE && (rOutputLocation == null || rOutputLocation.isBlank())) {
            throw new IllegalArgumentException("'outputLocation' is required when fetchType is STORE");
        }

        // DLI's shared 'default' queue rejects previewSqlJobResult outright ("Do not support use
        // default queue to getJobResult") — a permanent Huawei DLI limitation, not a transient error.
        // Fail fast before submitting the job rather than surfacing that raw gateway error ~26s later.
        if ((rFetchType == FetchType.FETCH || rFetchType == FetchType.FETCH_ONE) && isDefaultQueue(rQueue)) {
            throw new IllegalArgumentException(
                "DLI's shared 'default' queue does not support fetching query results via the preview API " +
                    "(fetchType FETCH/FETCH_ONE). Use fetchType STORE with an outputLocation to export the full " +
                    "result set to OBS (which the default queue supports), use fetchType NONE if you don't need " +
                    "the result set, or run the query on a dedicated DLI SQL queue by setting the 'queue' property.");
        }

        var client = client(runContext);
        var logger = runContext.logger();

        var submitted = DliService.submitJob(client, rSql, rDatabase, rQueue, rConf, rTags);
        var jobId = submitted.getJobId();
        killable.set(() -> DliService.cancelQuietly(client, jobId, logger));
        // Closes the submit->set race: if kill() ran (and found killable still null) between the
        // job going live on DLI and this line, re-invoke it now against the freshly-set killable.
        // cancelQuietly is idempotent, so this is safe even if kill() never actually raced.
        if (isKilled.get()) {
            killable.get().run();
        }

        logger.info("Submitted DLI job '{}' (type={}, mode={})", jobId, submitted.getJobType(), submitted.getJobMode());

        var deadline = System.currentTimeMillis() + rMaxDuration.toMillis();
        var status = DliService.pollUntilTerminal(client, jobId, rInterval, rMaxDuration, logger);

        logger.info("DLI job '{}' finished with status={}", jobId, status.getStatus());
        recordDurationMetric(runContext, status);

        // Derived from `submitted` (not `status`) so the output value and the QUERY decision below
        // always agree — the two responses use distinct JobTypeEnum classes for the same concept.
        var jobType = submitted.getJobType() != null ? submitted.getJobType().getValue() : null;
        var isQuery = submitted.getJobType() == CreateSqlJobResponse.JobTypeEnum.QUERY;

        if (rFetchType == FetchType.NONE || !isQuery) {
            return Output.builder().jobId(jobId).jobType(jobType).status(status.getStatus().getValue()).build();
        }

        return switch (rFetchType) {
            case FETCH -> fetchAll(runContext, client, jobId, rQueue, jobType, status, submitted);
            case FETCH_ONE -> fetchOne(runContext, client, jobId, rQueue, jobType, status, submitted);
            case STORE -> store(runContext, client, jobId, rQueue, rOutputLocation, jobType, status,
                remainingDuration(deadline), rInterval);
            case NONE -> throw new IllegalStateException("unreachable");
        };
    }

    private Output fetchAll(
        RunContext runContext, DliClient client, String jobId, String queue,
        String jobType, ShowSqlJobStatusResponse status, CreateSqlJobResponse submitted
    ) {
        var rows = resolveRows(runContext, client, jobId, queue, submitted);
        runContext.metric(Counter.of("dli.query.rows", rows.size()));
        return Output.builder().jobId(jobId).jobType(jobType).status(status.getStatus().getValue())
            .rows(rows).size((long) rows.size()).build();
    }

    private Output fetchOne(
        RunContext runContext, DliClient client, String jobId, String queue,
        String jobType, ShowSqlJobStatusResponse status, CreateSqlJobResponse submitted
    ) {
        var rows = resolveRows(runContext, client, jobId, queue, submitted);
        var row = rows.isEmpty() ? null : rows.getFirst();
        runContext.metric(Counter.of("dli.query.rows", row != null ? 1 : 0));
        return Output.builder().jobId(jobId).jobType(jobType).status(status.getStatus().getValue())
            .row(row).size(row != null ? 1L : 0L).build();
    }

    /** Prefers inline `job_mode=sync` results; otherwise falls back to a `previewSqlJobResult` call. */
    private static List<Map<String, Object>> resolveRows(
        RunContext runContext, DliClient client, String jobId, String queue, CreateSqlJobResponse submitted
    ) {
        var inline = inlineRows(submitted);
        if (inline != null) {
            return inline;
        }
        var preview = DliService.preview(client, jobId, queue);
        if (preview.getRowCount() != null && preview.getRowCount() > PREVIEW_ROW_CAP) {
            runContext.logger().warn(
                "DLI job '{}' returned {} rows, but the preview API caps results at {} — use fetchType=STORE for full results.",
                jobId, preview.getRowCount(), PREVIEW_ROW_CAP);
        }
        return zipRows(schemaOf(preview, jobId), preview.getRows());
    }

    private Output store(
        RunContext runContext, DliClient client, String jobId, String queue,
        String outputLocationUri, String jobType, ShowSqlJobStatusResponse status,
        Duration remainingDuration, Duration pollInterval
    ) throws Exception {
        // Scoped to the query jobId (unique per submission) so concurrent or repeated runs against
        // the same outputLocation never mix or clobber each other's exported result data.
        var scopedOutputLocation = DliService.scopedExportPath(outputLocationUri, jobId);
        var parsedOutput = DliService.parseObsUri(scopedOutputLocation);
        var bucket = parsedOutput[0];
        var prefix = parsedOutput[1];

        var exportResponse = DliService.submitExport(client, jobId, scopedOutputLocation, queue);
        var exportJobId = exportResponse.getJobId();
        killable.set(() -> DliService.cancelQuietly(client, exportJobId, runContext.logger()));
        // Closes the submit->set race: without this, a kill() landing between the export job going
        // live and this line would still invoke the stale (query-job) killable, cancelling an
        // already-terminal job and leaving the freshly submitted export job running and billing.
        if (isKilled.get()) {
            killable.get().run();
        }

        runContext.logger().info("Submitted DLI export job '{}' for query job '{}' to {}", exportJobId, jobId, scopedOutputLocation);
        DliService.pollUntilTerminal(client, exportJobId, pollInterval, remainingDuration, runContext.logger());

        var config = huaweiClientConfig(runContext);
        var rObsEndpointOverride = runContext.render(obsEndpointOverride).as(String.class).orElse(null);
        var rObsPathStyle = runContext.render(obsPathStyleAccess).as(Boolean.class).orElse(false);
        var rObsAuthType = runContext.render(obsAuthType).as(AuthType.class).orElse(AuthType.OBS);
        var rEndpointSuffix = runContext.render(endpointSuffix).as(String.class).orElse(null);

        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        long rowCount;
        try (
            var obs = ObsService.buildClient(config, rObsEndpointOverride, rObsPathStyle, rObsAuthType, rEndpointSuffix);
            var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)
        ) {
            rowCount = DliService.readExportedRowsToIon(obs, bucket, prefix, output);
            output.flush();
        }

        runContext.metric(Counter.of("dli.query.rows", rowCount));
        runContext.logger().info("Stored {} row(s) from DLI job '{}' into Kestra internal storage", rowCount, jobId);

        return Output.builder()
            .jobId(jobId)
            .jobType(jobType)
            .status(status.getStatus().getValue())
            .uri(runContext.storage().putFile(tempFile))
            .size(rowCount)
            .build();
    }

    private static boolean isDefaultQueue(String queue) {
        return queue == null || queue.isBlank() || queue.trim().equalsIgnoreCase("default");
    }

    private static Duration remainingDuration(long deadlineEpochMillis) {
        var remaining = deadlineEpochMillis - System.currentTimeMillis();
        return Duration.ofMillis(Math.max(remaining, 1000));
    }

    private void recordDurationMetric(RunContext runContext, ShowSqlJobStatusResponse status) {
        if (status.getDuration() != null) {
            runContext.metric(Timer.of("dli.query.duration", Duration.ofMillis(status.getDuration())));
        }
    }

    /** Non-null only for `job_mode=sync` responses that carry inline results; otherwise a preview call is required. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> inlineRows(CreateSqlJobResponse submitted) {
        if (submitted.getSchema() == null || submitted.getRows() == null) {
            return null;
        }
        List<Map<String, Object>> schema;
        try {
            schema = submitted.getSchema().stream()
                .map(o -> (Map<String, Object>) o)
                .toList();
        } catch (ClassCastException e) {
            throw new IllegalStateException(
                "DLI job '" + submitted.getJobId() + "' returned an unexpected inline schema shape " +
                "(expected a list of column maps) — this may indicate an incompatible DLI API response format.", e);
        }
        return zipRows(schema, submitted.getRows());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> schemaOf(PreviewSqlJobResultResponse preview, String jobId) {
        if (preview.getSchema() == null) {
            return List.of();
        }
        try {
            return preview.getSchema().stream()
                .map(m -> (Map<String, Object>) (Map<String, ?>) m)
                .toList();
        } catch (ClassCastException e) {
            throw new IllegalStateException(
                "DLI job '" + jobId + "' returned an unexpected preview schema shape " +
                "(expected a list of column maps) — this may indicate an incompatible DLI API response format.", e);
        }
    }

    /**
     * Zips a DLI result schema (ordered column `name`/`type` pairs) with raw row values into keyed
     * maps, coercing values by declared type where the JSON deserialization alone is ambiguous
     * (dates, timestamps, decimals) — mirroring Athena's `mapCell`.
     */
    private static List<Map<String, Object>> zipRows(List<Map<String, Object>> schema, List<List<Object>> rows) {
        if (rows == null) {
            return List.of();
        }
        var result = new ArrayList<Map<String, Object>>(rows.size());
        for (var row : rows) {
            var mapped = new LinkedHashMap<String, Object>();
            for (var i = 0; i < row.size(); i++) {
                var columnName = "col" + i;
                String columnType = null;
                if (schema != null && i < schema.size()) {
                    var column = schema.get(i);
                    columnName = column.get("name") != null ? String.valueOf(column.get("name")) : columnName;
                    columnType = column.get("type") != null ? String.valueOf(column.get("type")) : null;
                }
                mapped.put(columnName, mapCell(row.get(i), columnType));
            }
            result.add(mapped);
        }
        return result;
    }

    /** Best-effort type coercion; DLI's JSON deserialization already yields native types for most columns. */
    private static Object mapCell(Object rawValue, String declaredType) {
        if (rawValue == null || declaredType == null) {
            return rawValue;
        }
        var type = declaredType.toLowerCase();
        try {
            if (type.contains("decimal") && rawValue instanceof String s) {
                return new BigDecimal(s);
            }
            if ((type.contains("timestamp") || type.contains("date")) && rawValue instanceof Number n) {
                return Instant.ofEpochMilli(n.longValue());
            }
        } catch (NumberFormatException ignored) {
            // fall through and return the raw value rather than fail the task on an unexpected format
        }
        return rawValue;
    }

    @Override
    public void kill() {
        if (isKilled.compareAndSet(false, true)) {
            Optional.ofNullable(killable.get()).ifPresent(Runnable::run);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "DLI job ID of the SQL query")
        private final String jobId;

        @Schema(title = "DLI job type", description = "E.g. `QUERY`, `DDL`, `DCL`, `INSERT`.")
        private final String jobType;

        @Schema(title = "Terminal job status", description = "`FINISHED`, `FAILED`, or `CANCELLED`.")
        private final String status;

        @Schema(title = "Result rows", description = "Populated only when `fetchType` is `FETCH`.")
        private final List<Map<String, Object>> rows;

        @Schema(title = "First result row", description = "Populated only when `fetchType` is `FETCH_ONE`.")
        private final Map<String, Object> row;

        @Schema(title = "URI of the ION file in Kestra internal storage containing the result set", description = "Populated only when `fetchType` is `STORE`.")
        private final URI uri;

        @Schema(title = "Number of rows fetched or stored")
        private final Long size;
    }
}
