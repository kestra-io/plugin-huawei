package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.huawei.AbstractConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for Huawei Cloud GeminiDB for NoSQL (DynamoDB-Compatible API) tasks.
 *
 * <p>GeminiDB exposes a wire-compatible DynamoDB data-plane API over HTTPS with SigV4 AK/SK
 * signing (Huawei's own docs connect via boto3 with an explicit {@code endpoint_url}). There is no
 * Huawei-specific SDK for item-level operations, so the AWS SDK v2 {@code dynamodb} module is used
 * directly as the transport, pointed at the instance's connection address instead of an AWS region.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractGeminiDb extends AbstractConnection {

    protected static final int MIN_LIMIT = 1;
    protected static final int MAX_LIMIT = 1000;

    // SigV4 requires a region string to compute the signature, but GeminiDB routes purely by
    // `endpoint` — the region has no effect on where the request is sent, only on the signature.
    private static final String DEFAULT_SIGNING_REGION = "cn-north-1";

    // GeminiDB DynamoDB-compatible instances are addressed by a per-instance connection address —
    // unlike every other Huawei service in this plugin, there is no region-derived host to fall
    // back to, so `endpoint` is always required.
    @Schema(
        title = "GeminiDB instance connection address",
        description = """
            The DynamoDB-compatible API endpoint of the GeminiDB for NoSQL instance, e.g.
            `https://192.168.0.10:8635`. Find it on the instance's "Connection Management" page in
            the Huawei Cloud console. Unlike other Huawei Cloud services, this address is
            per-instance and cannot be derived from `region`.

            `region` is used only for SigV4 request signing and does not affect routing — GeminiDB
            routes solely by this `endpoint` property; leave `region` at its default unless signing
            requires a specific value.
            """
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> endpoint;

    @Schema(
        title = "Table name",
        description = "Target GeminiDB (DynamoDB-compatible) table for the operation."
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> tableName;

    protected DynamoDbClient client(final RunContext runContext) throws Exception {
        var config = huaweiClientConfig(runContext);

        var rEndpoint = runContext.render(this.endpoint).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "GeminiDB requires the 'endpoint' property — set it to the DynamoDB-compatible " +
                "connection address of the GeminiDB instance (e.g. https://192.168.0.10:8635)."));

        URI endpointUri;
        try {
            endpointUri = URI.create(rEndpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid 'endpoint' value '" + rEndpoint + "' — must be a valid URI such as " +
                "https://192.168.0.10:8635", e);
        }

        if (config.accessKeyId() == null || config.accessKeyId().isBlank()) {
            throw new IllegalArgumentException(
                "AK/SK credentials are required — set 'accessKeyId' and 'secretAccessKey' properties, " +
                "or configure 'temporaryCredentials' for inline IAM credential exchange.");
        }
        if (config.secretAccessKey() == null || config.secretAccessKey().isBlank()) {
            throw new IllegalArgumentException(
                "AK/SK credentials are incomplete — 'secretAccessKey' is required when 'accessKeyId' is set.");
        }

        var credentials = (config.securityToken() != null && !config.securityToken().isBlank())
            ? AwsSessionCredentials.create(config.accessKeyId(), config.secretAccessKey(), config.securityToken())
            : AwsBasicCredentials.create(config.accessKeyId(), config.secretAccessKey());

        var rRegion = (config.region() != null && !config.region().isBlank()) ? config.region() : DEFAULT_SIGNING_REGION;

        return DynamoDbClient.builder()
            .endpointOverride(endpointUri)
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .region(Region.of(rRegion))
            .build();
    }

    protected String renderedTableName(final RunContext runContext) throws Exception {
        return runContext.render(this.tableName).as(String.class)
            .orElseThrow(() -> new IllegalStateException(
                "'tableName' rendered to an empty value — check the property and any templated expression it contains."));
    }

    // `limit` can't carry @Min/@Max directly: Hibernate Validator has no ValueExtractor for
    // Property<>, so those annotations blow up flow-save-time bean validation with HV000030. The
    // bound is enforced here instead, at render time.
    protected int renderedLimit(final RunContext runContext, final Property<Integer> limit) throws Exception {
        int rLimit = runContext.render(limit).as(Integer.class).orElse(100);
        if (rLimit < MIN_LIMIT || rLimit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                "'limit' must be between " + MIN_LIMIT + " and " + MAX_LIMIT + " (was " + rLimit + ").");
        }
        return rLimit;
    }

    protected Map<String, Object> objectMapFrom(Map<String, AttributeValue> fields) {
        var row = new HashMap<String, Object>();
        fields.forEach((key, value) -> row.put(key, objectFrom(value)));
        return row;
    }

    protected Object objectFrom(AttributeValue value) {
        if (value == null || Boolean.TRUE.equals(value.nul())) {
            return null;
        }
        if (value.bool() != null) {
            return value.bool();
        }
        if (value.hasSs()) {
            return value.ss();
        }
        if (value.hasL()) {
            return value.l().stream().map(this::objectFrom).toList();
        }
        if (value.hasM()) {
            return objectMapFrom(value.m());
        }

        // We may miss some cases (numbers, binary), but this covers the common shapes for a first
        // implementation — mirrors io.kestra.plugin.aws.dynamodb.AbstractDynamoDb.
        return value.s();
    }

    protected Map<String, AttributeValue> valueMapFrom(Map<String, Object> fields) {
        var item = new HashMap<String, AttributeValue>();
        fields.forEach((key, value) -> item.put(key, objectFrom(value)));
        return item;
    }

    @SuppressWarnings("unchecked")
    protected AttributeValue objectFrom(Object value) {
        if (value == null) {
            return AttributeValue.fromNul(true);
        }
        if (value instanceof String s) {
            return AttributeValue.fromS(s);
        }
        if (value instanceof Boolean b) {
            return AttributeValue.fromBool(b);
        }
        if (value instanceof List<?> list) {
            return AttributeValue.fromL(list.stream().map(this::objectFrom).toList());
        }
        if (value instanceof Map<?, ?> map) {
            return AttributeValue.fromM(valueMapFrom((Map<String, Object>) map));
        }

        // Numbers and any other type fall back to their string form: GeminiDB then stores them as
        // an "S" attribute, not "N" — the same limitation as the AWS DynamoDB task this is ported from.
        return AttributeValue.fromS(value.toString());
    }

    /**
     * Logs a message when the response was truncated to a single page: {@code Query}/{@code Scan}
     * never follow {@code LastEvaluatedKey}, so a truncated result could otherwise go unnoticed.
     */
    protected void warnIfTruncated(final RunContext runContext, boolean hasMoreResults, String operation) throws Exception {
        if (hasMoreResults) {
            runContext.logger().info(
                "GeminiDB {} on table '{}' returned a LastEvaluatedKey — more items are available but " +
                "this task only reads a single page. Narrow the {} or raise 'limit' to retrieve more.",
                operation, renderedTableName(runContext), operation);
        }
    }

    protected FetchOutput fetchOutputs(
        final List<Map<String, AttributeValue>> items,
        final FetchType fetchType,
        final RunContext runContext
    ) throws Exception {
        var outputBuilder = FetchOutput.builder();

        switch (fetchType) {
            case FETCH -> {
                var rows = items.stream().<Object>map(this::objectMapFrom).toList();
                outputBuilder.rows(rows).size((long) rows.size());
            }
            case FETCH_ONE -> {
                var row = items.stream().findFirst().map(this::objectMapFrom).orElse(null);
                outputBuilder.row(row).size(row != null ? 1L : 0L);
            }
            case STORE -> {
                var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                long count = 0;
                try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
                    for (var item : items) {
                        FileSerde.write(output, objectMapFrom(item));
                        count++;
                    }
                    output.flush();
                }
                outputBuilder.uri(runContext.storage().putFile(tempFile)).size(count);
            }
            case NONE -> outputBuilder.size(0L);
        }

        var output = outputBuilder.build();
        runContext.metric(Counter.of("records", output.getSize(), "tableName", renderedTableName(runContext)));

        return output;
    }
}
