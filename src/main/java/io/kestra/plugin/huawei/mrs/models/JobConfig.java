package io.kestra.plugin.huawei.mrs.models;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.plugin.huawei.mrs.JobType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;
import java.util.Map;

@Value
@Builder
@Jacksonized
public class JobConfig {

    @Schema(title = "Job execution type")
    @NotNull
    @PluginProperty(group = "main")
    Property<JobType> jobType;

    @Schema(title = "Job name", description = "A unique, human-readable name for this job step within the cluster.")
    @NotNull
    @PluginProperty(group = "main")
    Property<String> jobName;

    @Schema(
        title = "Job arguments",
        description = "Positional arguments passed to the job, e.g. the main class, jar path, or script arguments."
    )
    @PluginProperty(group = "main")
    Property<List<String>> arguments;

    @Schema(title = "Extra job configuration entries", description = "Key/value pairs forwarded as-is to the job's `properties` field.")
    @PluginProperty(group = "advanced")
    Property<Map<String, String>> properties;
}
