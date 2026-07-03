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

        var customEndpoint = (rOverride != null && !rOverride.isBlank()) || (rSuffix != null && !rSuffix.isBlank());

        if (customEndpoint && (config.projectId() == null || config.projectId().isBlank())) {
            // The CES v1 APIs embed the project id in the request path (`/V1.0/{project_id}/...`). When the
            // SDK resolves the endpoint from its region enum it can auto-discover the project, but a custom
            // endpoint (endpointOverride or endpointSuffix — e.g. sovereign clouds) bypasses that, leaving
            // `{project_id}` unsubstituted and the gateway rejecting the call with an opaque 400
            // (APIGW / ces.0047 "URI incorrect."). Fail fast with an actionable message instead.
            throw new IllegalArgumentException(
                "CES requires `projectId` when a custom endpoint (`endpointOverride` or `endpointSuffix`) is used — " +
                "set the 'projectId' property to your Huawei Cloud project ID for the target region " +
                "(found in the console under 'My Credentials' → 'API Credentials').");
        }

        if (rOverride != null && !rOverride.isBlank()) {
            builder.withEndpoint(CesUtils.stripTrailingSlash(rOverride.trim()));
        } else if (rRegion != null && !rRegion.isBlank()) {
            // An explicit endpointSuffix forces suffix-derived resolution even for regions present in the SDK
            // enum: the enum hard-codes the `.myhuaweicloud.com` domain, which is wrong for sovereign clouds
            // (e.g. `myhuaweicloud.eu`). Without a suffix, prefer the SDK enum and fall back to derivation only
            // for regions not yet in it (e.g. newly added regions).
            if (rSuffix != null && !rSuffix.isBlank()) {
                builder.withEndpoint(CesUtils.cesEndpoint(null, rRegion, rSuffix));
            } else {
                try {
                    builder.withRegion(CesRegion.valueOf(rRegion));
                } catch (IllegalArgumentException e) {
                    builder.withEndpoint(CesUtils.cesEndpoint(null, rRegion, null));
                }
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
