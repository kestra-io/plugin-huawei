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
 * <p>GeminiDB exposes a wire-compatible DynamoDB data-plane API with SigV4 signing (Huawei's own
 * docs connect via boto3 with an explicit {@code endpoint_url}). There is no Huawei-specific SDK for
 * item-level operations, so the AWS SDK v2 {@code dynamodb} module is used directly as the
 * transport, pointed at the instance's connection address instead of an AWS region.
 *
 * <p><strong>The credentials are the instance's database account, not Huawei IAM.</strong> The
 * data plane is served by the GeminiDB instance itself rather than through the API gateway, so it
 * never consults IAM: {@code accessKeyId} must be the database username ({@code rwuser}) and
 * {@code secretAccessKey} the admin password set when the instance was bought. A perfectly valid
 * IAM AK/SK — even with {@code GeminiDB FullAccess} correctly scoped to the instance's project —
 * is rejected with a bare {@code AccessDeniedException: auth failed} carrying no error code and no
 * detail. Verified live against {@code ap-southeast-3} on 2026-07-29. Consequently
 * {@code securityToken} and {@code temporaryCredentials} cannot work here at all; {@link
 * #client(RunContext)} rejects them up front rather than letting them fail as an opaque auth error.
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
            `http://192.168.0.10:8000`. Find it under *Connections* on the instance's console page.
            Unlike other Huawei Cloud services, this address is per-instance and cannot be derived
            from `region`.

            The data-plane port is **8000** and is fixed: it cannot be chosen at creation or changed
            afterwards (a high-availability port 80 is also documented). Do not use 8635 — that is
            the Cassandra/CQL port of the underlying kernel and does not speak the DynamoDB
            protocol.

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

    // The inherited @Schema on AbstractConnectionInterface ("Huawei Cloud access key ... not
    // required when providing a pre-obtained securityToken") is correct for every other service in
    // this plugin, but wrong here: GeminiDB's data plane authenticates against the instance's
    // database account, not IAM. Overridden so the UI tooltip a user actually reads states that.
    @Schema(
        title = "GeminiDB database account username",
        description = "The DynamoDB-compatible data plane authenticates against the instance's own " +
            "database account, not Huawei IAM — set this to the fixed database username `rwuser`. " +
            "**Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @PluginProperty(group = "connection", secret = true)
    @Override
    public Property<String> getAccessKeyId() {
        return super.getAccessKeyId();
    }

    @Schema(
        title = "GeminiDB database account password",
        description = "The instance admin password set when the GeminiDB instance was purchased — " +
            "it cannot be retrieved later, only reset. Paired with `accessKeyId: rwuser`. " +
            "**Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @PluginProperty(group = "connection", secret = true)
    @Override
    public Property<String> getSecretAccessKey() {
        return super.getSecretAccessKey();
    }

    @Schema(
        title = "Not supported by GeminiDB",
        description = "GeminiDB's DynamoDB-compatible data plane never consults Huawei IAM, so a " +
            "security token (or an inline `temporaryCredentials` exchange) has nothing to " +
            "authenticate against. Setting either causes the task to fail fast with an actionable " +
            "error instead of the opaque `AccessDeniedException: auth failed` a real GeminiDB " +
            "instance would otherwise return. Use `accessKeyId`/`secretAccessKey` instead."
    )
    @PluginProperty(group = "connection", secret = true)
    @Override
    public Property<String> getSecurityToken() {
        return super.getSecurityToken();
    }

    protected DynamoDbClient client(final RunContext runContext) throws Exception {
        // The data plane authenticates against the instance's own database account, so an STS triple
        // (direct `securityToken` or an inline `temporaryCredentials` exchange) has nothing to
        // authenticate against. Checked here, before `huaweiClientConfig(runContext)`, so a
        // `temporaryCredentials` default doesn't burn a live IAM STS round-trip only to be rejected
        // afterward — and so the exchange's own failures never mask this message with an unrelated
        // IAM auth error. `getTemporaryCredentials()` is a presence check only, not rendered: knowing
        // the property was set at all is enough to reject it.
        var rSecurityToken = runContext.render(this.getSecurityToken()).as(String.class).orElse(null);
        if ((rSecurityToken != null && !rSecurityToken.isBlank()) || this.getTemporaryCredentials() != null) {
            throw new IllegalArgumentException(
                "GeminiDB does not support 'securityToken' or 'temporaryCredentials': the " +
                "DynamoDB-compatible data plane authenticates against the instance's database " +
                "account, not Huawei IAM, so temporary IAM credentials can never be accepted. " +
                "Use 'accessKeyId: rwuser' with the instance admin password as 'secretAccessKey'.");
        }

        var config = huaweiClientConfig(runContext);

        var rEndpoint = runContext.render(this.endpoint).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "GeminiDB requires the 'endpoint' property — set it to the DynamoDB-compatible " +
                "connection address of the GeminiDB instance (e.g. http://192.168.0.10:8000)."));

        URI endpointUri;
        try {
            endpointUri = URI.create(rEndpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid 'endpoint' value '" + rEndpoint + "' — must be a valid URI such as " +
                "http://192.168.0.10:8000", e);
        }

        if (config.accessKeyId() == null || config.accessKeyId().isBlank()) {
            throw new IllegalArgumentException(
                "GeminiDB requires database-account credentials — set 'accessKeyId' to the database " +
                "username ('rwuser') and 'secretAccessKey' to the admin password set when the " +
                "instance was bought. IAM AK/SK credentials do not work: the DynamoDB-compatible " +
                "data plane is served by the instance itself and never consults IAM.");
        }
        if (config.secretAccessKey() == null || config.secretAccessKey().isBlank()) {
            throw new IllegalArgumentException(
                "GeminiDB credentials are incomplete — 'secretAccessKey' (the instance admin " +
                "password) is required when 'accessKeyId' is set.");
        }

        // No securityToken re-check needed here: the guard above already rejects any request that
        // would otherwise reach huaweiClientConfig with a securityToken or temporaryCredentials set,
        // so config.securityToken() is always null/blank by the time this line runs.
        var credentials = AwsBasicCredentials.create(config.accessKeyId(), config.secretAccessKey());

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
    protected void warnIfTruncated(final RunContext runContext, boolean hasMoreResults, String operation, String rTableName) {
        if (hasMoreResults) {
            runContext.logger().info(
                "GeminiDB {} on table '{}' returned a LastEvaluatedKey — more items are available but " +
                "this task only reads a single page. Narrow the {} or raise 'limit' to retrieve more.",
                operation, rTableName, operation);
        }
    }

    protected FetchOutput fetchOutputs(
        final List<Map<String, AttributeValue>> items,
        final FetchType fetchType,
        final RunContext runContext,
        final String rTableName
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
        runContext.metric(Counter.of("records", output.getSize(), "tableName", rTableName));

        return output;
    }
}
