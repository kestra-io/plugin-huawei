package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
public abstract class AbstractObsObject extends AbstractObs {

    @Schema(
        title = "OBS bucket name.",
        description = "Name of the OBS bucket to operate on. The bucket must already exist."
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> bucket;
}
