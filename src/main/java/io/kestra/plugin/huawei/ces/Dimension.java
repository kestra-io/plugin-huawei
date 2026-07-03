package io.kestra.plugin.huawei.ces;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class Dimension {

    @Schema(title = "Dimension name", description = "E.g. `instance_id` for an ECS instance dimension.")
    @NotNull
    @PluginProperty(group = "main")
    Property<String> name;

    @Schema(title = "Dimension value", description = "E.g. the ECS instance ID.")
    @NotNull
    @PluginProperty(group = "main")
    Property<String> value;
}
