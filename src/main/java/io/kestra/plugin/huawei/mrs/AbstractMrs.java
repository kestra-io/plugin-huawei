package io.kestra.plugin.huawei.mrs;

import com.huaweicloud.sdk.core.ClientBuilder;
import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.core.region.Region;
import com.huaweicloud.sdk.mrs.v2.MrsClient;
import com.huaweicloud.sdk.mrs.v2.region.MrsRegion;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.function.Function;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractMrs extends AbstractConnection implements MrsConnectionInterface {

    protected Property<String> endpointOverride;

    protected Property<String> endpointSuffix;

    /** V2 client — used for job submission, job status/cancellation, and cluster+job creation (`runJobFlow`). */
    protected MrsClient client(RunContext runContext) throws Exception {
        return buildClient(runContext, MrsClient.newBuilder(), MrsRegion::valueOf);
    }

    /**
     * V1 client — MRS v2 has no cluster-detail or delete-cluster endpoint, so cluster status polling
     * and deletion go through the v1 API instead. Both API versions resolve to the same host
     * (`mrs.<region>.myhuaweicloud.com`), so endpoint resolution is identical; only the region-enum
     * class differs.
     */
    protected com.huaweicloud.sdk.mrs.v1.MrsClient clientV1(RunContext runContext) throws Exception {
        return buildClient(
            runContext,
            com.huaweicloud.sdk.mrs.v1.MrsClient.newBuilder(),
            com.huaweicloud.sdk.mrs.v1.region.MrsRegion::valueOf);
    }

    private <T> T buildClient(
        RunContext runContext,
        ClientBuilder<T> builder,
        Function<String, Region> regionResolver
    ) throws Exception {
        var config = huaweiClientConfig(runContext);
        var rOverride = runContext.render(endpointOverride).as(String.class).orElse(null);
        var rRegion = runContext.render(region).as(String.class).orElse(null);
        var rSuffix = runContext.render(endpointSuffix).as(String.class).orElse(null);

        MrsUtils.requireProjectIdForCustomEndpoint(rOverride, rSuffix, config.projectId());

        builder.withCredential(buildCredentials(config));

        if (rOverride != null && !rOverride.isBlank()) {
            builder.withEndpoint(MrsUtils.stripTrailingSlash(rOverride.trim()));
        } else if (rRegion != null && !rRegion.isBlank()) {
            // An explicit endpointSuffix forces suffix-derived resolution even for regions present in the
            // SDK enum: the enum hard-codes the `.myhuaweicloud.com` domain, which is wrong for sovereign
            // clouds (e.g. `myhuaweicloud.eu`). Without a suffix, prefer the SDK enum and fall back to
            // derivation only for regions not yet in it (e.g. newly added regions).
            if (rSuffix != null && !rSuffix.isBlank()) {
                builder.withEndpoint(MrsUtils.mrsEndpoint(null, rRegion, rSuffix));
            } else {
                try {
                    builder.withRegion(regionResolver.apply(rRegion));
                } catch (IllegalArgumentException e) {
                    builder.withEndpoint(MrsUtils.mrsEndpoint(null, rRegion, null));
                }
            }
        } else {
            throw new IllegalArgumentException(
                "MRS requires either `endpointOverride` or `region` to be set — " +
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
