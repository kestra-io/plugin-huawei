package io.kestra.plugin.huawei.mrs.models;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class NodeGroupConfig {

    @Schema(
        title = "Node group name",
        description = "The role of this node group, e.g. `master_node_default_group`, `core_node_default_group`, or `task_node_default_group` — must match a group name accepted by the chosen `clusterVersion`/`clusterType`."
    )
    @NotNull
    @PluginProperty(group = "main")
    Property<String> groupName;

    @Schema(title = "Number of nodes in this group")
    @NotNull
    @Min(1)
    @PluginProperty(group = "main")
    Property<Integer> nodeNum;

    @Schema(title = "ECS flavor for nodes in this group", description = "E.g. `c6.4xlarge.4`. See the MRS console for flavors available in your region.")
    @NotNull
    @PluginProperty(group = "main")
    Property<String> nodeSize;

    @Schema(title = "Root volume type", description = "Defaults to `SATA`.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<String> rootVolumeType = Property.ofValue("SATA");

    @Schema(title = "Root volume size (GB)", description = "Defaults to 40 GB.")
    @Builder.Default
    @Min(1)
    @PluginProperty(group = "advanced")
    Property<Integer> rootVolumeSize = Property.ofValue(40);

    @Schema(title = "Data volume type", description = "Defaults to `SATA`.")
    @Builder.Default
    @PluginProperty(group = "advanced")
    Property<String> dataVolumeType = Property.ofValue("SATA");

    @Schema(title = "Data volume size (GB)", description = "Defaults to 100 GB.")
    @Builder.Default
    @Min(1)
    @PluginProperty(group = "advanced")
    Property<Integer> dataVolumeSize = Property.ofValue(100);

    @Schema(title = "Number of data volumes per node", description = "Defaults to 1.")
    @Builder.Default
    @Min(1)
    @Max(20)
    @PluginProperty(group = "advanced")
    Property<Integer> dataVolumeCount = Property.ofValue(1);
}
