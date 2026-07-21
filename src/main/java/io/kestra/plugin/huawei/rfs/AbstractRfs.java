package io.kestra.plugin.huawei.rfs;

import com.huaweicloud.sdk.aos.v1.AosClient;
import com.huaweicloud.sdk.aos.v1.region.AosRegion;
import com.huaweicloud.sdk.core.auth.BasicCredentials;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractRfs extends AbstractConnection implements RfsConnectionInterface {

    @NotNull
    @Schema(
        title = "Stack name",
        description = "RFS stack identifier used by create, deploy, and delete operations. Must be unique within the project."
    )
    @PluginProperty(group = "main")
    protected Property<String> stackName;

    protected Property<String> endpointOverride;

    protected Property<String> endpointSuffix;

    @Builder.Default
    @Schema(
        title = "Wait for completion",
        description = "When `true` (the default), block until the stack deployment or deletion reaches a terminal state."
    )
    @PluginProperty(group = "execution")
    protected Property<Boolean> wait = Property.ofValue(true);

    @Builder.Default
    @Schema(
        title = "Maximum time to wait for the stack operation to complete",
        description = "ISO-8601 duration (e.g. `PT1H`). Only relevant when `wait` is `true`. Defaults to 1 hour."
    )
    @PluginProperty(group = "execution")
    protected Property<Duration> maxDuration = Property.ofValue(Duration.ofHours(1));

    @Builder.Default
    @Schema(
        title = "Polling interval while waiting for the stack operation to complete",
        description = "ISO-8601 duration (e.g. `PT5S`). Defaults to 5 seconds."
    )
    @PluginProperty(group = "execution")
    protected Property<Duration> interval = Property.ofValue(Duration.ofSeconds(5));

    protected AosClient client(RunContext runContext) throws Exception {
        var config = huaweiClientConfig(runContext);
        var rOverride = runContext.render(endpointOverride).as(String.class).orElse(null);
        var rRegion = runContext.render(region).as(String.class).orElse(null);
        var rSuffix = runContext.render(endpointSuffix).as(String.class).orElse(null);

        var creds = buildCredentials(config);
        var builder = AosClient.newBuilder().withCredential(creds);

        RfsUtils.requireProjectIdForCustomEndpoint(rOverride, rSuffix, config.projectId());

        if (rOverride != null && !rOverride.isBlank()) {
            builder.withEndpoint(RfsUtils.stripTrailingSlash(rOverride.trim()));
        } else if (rRegion != null && !rRegion.isBlank()) {
            // Force our own `rfs.<region>.<suffix>` derivation (via withEndpoint) when either an explicit
            // endpointSuffix is set, or the region is a sovereign region whose SDK-baked endpoint is wrong:
            // AosRegion hard-codes eu-west-101 to `aos.myhuaweicloud.eu`, which lacks the region segment and
            // does not resolve. Deriving bypasses the SDK's project-id auto-discovery, so projectId is required.
            if ((rSuffix != null && !rSuffix.isBlank()) || RfsUtils.isSovereignRegion(rRegion)) {
                if (config.projectId() == null || config.projectId().isBlank()) {
                    throw new IllegalArgumentException(
                        "RFS requires `projectId` for region '" + rRegion + "' (its endpoint is resolved via a " +
                        "custom or sovereign-region host that bypasses the SDK's project auto-discovery) — set the " +
                        "'projectId' property to your Huawei Cloud project ID (console → 'My Credentials' → 'API Credentials').");
                }
                builder.withEndpoint(RfsUtils.rfsEndpoint(null, rRegion, rSuffix));
            } else {
                // Standard region: prefer the SDK enum (enables project-id auto-discovery), falling back to
                // derivation only for regions not yet in it (e.g. newly added regions).
                try {
                    builder.withRegion(AosRegion.valueOf(rRegion));
                } catch (IllegalArgumentException e) {
                    builder.withEndpoint(RfsUtils.rfsEndpoint(null, rRegion, null));
                }
            }
        } else {
            throw new IllegalArgumentException(
                "RFS requires either `endpointOverride` or `region` to be set — " +
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
