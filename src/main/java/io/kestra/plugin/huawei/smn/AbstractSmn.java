package io.kestra.plugin.huawei.smn;

import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.smn.v2.SmnClient;
import com.huaweicloud.sdk.smn.v2.region.SmnRegion;
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
public abstract class AbstractSmn extends AbstractConnection implements SmnConnectionInterface {

    protected Property<String> endpointOverride;

    protected Property<String> endpointSuffix;

    protected SmnClient client(RunContext runContext) throws Exception {
        var config = huaweiClientConfig(runContext);
        var rOverride = runContext.render(endpointOverride).as(String.class).orElse(null);
        var rRegion = runContext.render(region).as(String.class).orElse(null);
        var rSuffix = runContext.render(endpointSuffix).as(String.class).orElse(null);

        var creds = buildCredentials(config);
        var builder = SmnClient.newBuilder().withCredential(creds);

        SmnUtils.requireProjectIdForCustomEndpoint(rOverride, rSuffix, config.projectId());

        if (rOverride != null && !rOverride.isBlank()) {
            builder.withEndpoint(SmnUtils.stripTrailingSlash(rOverride.trim()));
        } else if (rRegion != null && !rRegion.isBlank()) {
            // An explicit endpointSuffix forces suffix-derived resolution even for regions present in the SDK
            // enum: the enum hard-codes the `.myhuaweicloud.com` domain, which is wrong for sovereign clouds
            // (e.g. `myhuaweicloud.eu`). Without a suffix, prefer the SDK enum and fall back to derivation only
            // for regions not yet in it (e.g. newly added regions).
            if (rSuffix != null && !rSuffix.isBlank()) {
                builder.withEndpoint(SmnUtils.smnEndpoint(null, rRegion, rSuffix));
            } else {
                try {
                    builder.withRegion(SmnRegion.valueOf(rRegion));
                } catch (IllegalArgumentException e) {
                    builder.withEndpoint(SmnUtils.smnEndpoint(null, rRegion, null));
                }
            }
        } else {
            throw new IllegalArgumentException(
                "SMN requires either `endpointOverride` or `region` to be set — " +
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
