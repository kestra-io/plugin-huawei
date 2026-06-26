package io.kestra.plugin.huawei.functiongraph;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.plugin.huawei.AbstractConnectionInterface;
import io.swagger.v3.oas.annotations.media.Schema;

public interface FunctionGraphConnectionInterface extends AbstractConnectionInterface {

    @Schema(
        title = "FunctionGraph endpoint URL override.",
        description = """
            Overrides the default endpoint derived from `region` and `endpointSuffix`. Use this for
            private endpoints, non-standard deployments, or tests. When set, `endpointSuffix` is
            ignored.

            Format: `https://functiongraph.<region>.myhuaweicloud.com` (without trailing slash).
            """
    )
    @PluginProperty(group = "connection")
    Property<String> getEndpointOverride();

    @Schema(
        title = "Huawei Cloud domain suffix.",
        description = """
            Controls the top-level domain used when deriving the FunctionGraph endpoint from `region`.
            Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the Huawei Cloud European
            sovereign cloud.

            Ignored when `endpointOverride` is set.
            """
    )
    @PluginProperty(group = "connection")
    Property<String> getEndpointSuffix();
}
