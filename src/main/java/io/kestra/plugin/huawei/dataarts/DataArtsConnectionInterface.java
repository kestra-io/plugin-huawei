package io.kestra.plugin.huawei.dataarts;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.plugin.huawei.AbstractConnectionInterface;
import io.swagger.v3.oas.annotations.media.Schema;

public interface DataArtsConnectionInterface extends AbstractConnectionInterface {

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
    Property<String> getWorkspaceId();

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
    Property<String> getEndpointOverride();
}
