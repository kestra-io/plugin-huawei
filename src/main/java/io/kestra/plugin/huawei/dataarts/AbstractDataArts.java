package io.kestra.plugin.huawei.dataarts;

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
public abstract class AbstractDataArts extends AbstractConnection implements DataArtsConnectionInterface {

    @Schema(
        title = "DataArts Studio workspace ID.",
        description = """
            Identifies the DataArts Studio workspace to target. Required for all DataArts Factory
            operations when your account has multiple workspaces. When omitted, the default workspace
            is used.

            Find the workspace ID in the DataArts Studio console under **Workspaces → Settings**.
            """
    )
    @PluginProperty(group = "connection")
    protected Property<String> workspaceId;

    @Schema(
        title = "DataArts Studio endpoint URL override.",
        description = """
            Overrides the default endpoint derived from `region`. Use this when running against a
            private endpoint, a non-standard deployment, or in tests.

            When set, the `region` property is ignored for endpoint resolution. Format:
            `https://dataarts.<region>.myhuaweicloud.com` (without trailing slash).
            """
    )
    @PluginProperty(group = "connection")
    protected Property<String> endpointOverride;

    protected String resolvedEndpoint(RunContext runContext) throws Exception {
        var rOverride = runContext.render(endpointOverride).as(String.class).orElse(null);
        var rRegion = runContext.render(region).as(String.class).orElse(null);
        return DataArtsUtils.dataArtsEndpoint(rOverride, rRegion);
    }

    protected String resolvedWorkspaceId(RunContext runContext) throws Exception {
        return runContext.render(workspaceId).as(String.class).orElse(null);
    }

    protected String resolvedProjectId(RunContext runContext) throws Exception {
        return runContext.render(projectId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "projectId is required for DataArts Studio API calls — set the 'projectId' property " +
                "to the Huawei Cloud project ID of the region where the DataArts workspace is deployed."));
    }
}
