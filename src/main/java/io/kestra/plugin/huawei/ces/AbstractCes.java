package io.kestra.plugin.huawei.ces;

import com.huaweicloud.sdk.ces.v1.CesClient;
import com.huaweicloud.sdk.ces.v1.region.CesRegion;
import com.huaweicloud.sdk.core.auth.BasicCredentials;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
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
public abstract class AbstractCes extends AbstractConnection implements CesConnectionInterface {

    protected Property<String> endpointOverride;

    protected Property<String> endpointSuffix;

    protected CesClient client(RunContext runContext) throws Exception {
        var config = huaweiClientConfig(runContext);
        var rOverride = runContext.render(endpointOverride).as(String.class).orElse(null);
        var rRegion = runContext.render(region).as(String.class).orElse(null);
        var rSuffix = runContext.render(endpointSuffix).as(String.class).orElse(null);

        var creds = buildCredentials(config);
        var builder = CesClient.newBuilder().withCredential(creds);

        if (rOverride != null && !rOverride.isBlank()) {
            builder.withEndpoint(CesUtils.stripTrailingSlash(rOverride.trim()));
        } else if (rRegion != null && !rRegion.isBlank()) {
            // Fall back to endpoint derivation for regions not yet in the SDK enum (e.g. newly added sovereign-cloud regions).
            try {
                builder.withRegion(CesRegion.valueOf(rRegion));
            } catch (IllegalArgumentException e) {
                var derivedEndpoint = CesUtils.cesEndpoint(null, rRegion, rSuffix);
                builder.withEndpoint(derivedEndpoint);
            }
        } else {
            throw new IllegalArgumentException(
                "CES requires either `endpointOverride` or `region` to be set — " +
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
