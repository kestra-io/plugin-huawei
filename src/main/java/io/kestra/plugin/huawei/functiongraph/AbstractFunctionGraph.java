package io.kestra.plugin.huawei.functiongraph;

import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.functiongraph.v2.FunctionGraphClient;
import com.huaweicloud.sdk.functiongraph.v2.region.FunctionGraphRegion;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractFunctionGraph extends AbstractConnection implements FunctionGraphConnectionInterface {

    @Schema(
        title = "FunctionGraph endpoint URL override",
        description = """
            Overrides the default endpoint derived from `region` and `endpointSuffix`. Use this for
            private endpoints, non-standard deployments, or tests. When set, `endpointSuffix` is
            ignored.

            Format: `https://functiongraph.<region>.myhuaweicloud.com` (without trailing slash).
            """
    )
    @PluginProperty(group = "connection")
    protected Property<String> endpointOverride;

    @Schema(
        title = "Huawei Cloud domain suffix",
        description = """
            Controls the top-level domain used when deriving the FunctionGraph endpoint from `region`.
            Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the Huawei Cloud European
            sovereign cloud.

            Ignored when `endpointOverride` is set.
            """
    )
    @PluginProperty(group = "connection")
    protected Property<String> endpointSuffix;

    protected FunctionGraphClient client(RunContext runContext) throws Exception {
        var config = huaweiClientConfig(runContext);
        var rOverride = runContext.render(endpointOverride).as(String.class).orElse(null);
        var rRegion = runContext.render(region).as(String.class).orElse(null);
        var rSuffix = runContext.render(endpointSuffix).as(String.class).orElse(null);

        var creds = buildCredentials(config);
        var builder = FunctionGraphClient.newBuilder().withCredential(creds);

        if (rOverride != null && !rOverride.isBlank()) {
            builder.withEndpoint(FunctionGraphUtils.stripTrailingSlash(rOverride.trim()));
        } else if (rRegion != null && !rRegion.isBlank()) {
            // Fall back to endpoint derivation for regions not yet in the SDK enum (e.g. newly added sovereign-cloud regions).
            try {
                builder.withRegion(FunctionGraphRegion.valueOf(rRegion));
            } catch (IllegalArgumentException e) {
                var derivedEndpoint = FunctionGraphUtils.functionGraphEndpoint(null, rRegion, rSuffix);
                builder.withEndpoint(derivedEndpoint);
            }
        } else {
            throw new IllegalArgumentException(
                "FunctionGraph requires either `endpointOverride` or `region` to be set — " +
                "set the 'region' property (e.g. eu-west-101) or provide an explicit 'endpointOverride'.");
        }

        return builder.build();
    }

    private static BasicCredentials buildCredentials(AbstractConnection.HuaweiClientConfig config) {
        if (config.accessKeyId() == null || config.accessKeyId().isBlank()) {
            throw new IllegalArgumentException(
                "AK/SK credentials are required — set 'accessKeyId' and 'secretAccessKey' properties, " +
                "or configure 'temporaryCredentials' for inline IAM credential exchange.");
        }
        if (config.secretAccessKey() == null || config.secretAccessKey().isBlank()) {
            throw new IllegalArgumentException(
                "AK/SK credentials are incomplete — 'secretAccessKey' is required when 'accessKeyId' is set.");
        }
        var creds = new BasicCredentials()
            .withAk(config.accessKeyId())
            .withSk(config.secretAccessKey());
        if (config.securityToken() != null) {
            creds.withSecurityToken(config.securityToken());
        }
        if (config.projectId() != null) {
            creds.withProjectId(config.projectId());
        }
        return creds;
    }
}
