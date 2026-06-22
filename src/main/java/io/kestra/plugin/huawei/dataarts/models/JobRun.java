package io.kestra.plugin.huawei.dataarts.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.kestra.core.models.annotations.PluginProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobRun implements io.kestra.core.models.tasks.Output {

    @Schema(title = "Job name.")
    @PluginProperty(group = "main")
    private final String jobName;

    @Schema(title = "Job run instance ID.")
    @PluginProperty(group = "main")
    private final Long instanceId;

    @Schema(
        title = "Job run status.",
        description = """
            Lifecycle status of the job run instance:
            - `waiting` — queued, not yet started.
            - `running` — currently executing.
            - `success` — completed successfully.
            - `fail` — completed with an error.
            - `running-exception` — running but an exception was detected.
            - `pause` — paused by user.
            - `manual-stop` — stopped manually.
            """
    )
    @PluginProperty(group = "main")
    private final String status;

    @Schema(title = "Scheduled plan time (epoch milliseconds).")
    @PluginProperty(group = "main")
    private final Long planTime;

    @Schema(title = "Actual start time (epoch milliseconds).")
    @PluginProperty(group = "main")
    private final Long startTime;

    @Schema(title = "End time (epoch milliseconds); null if still running.")
    @PluginProperty(group = "main")
    private final Long endTime;

    @Schema(title = "Last update time (epoch milliseconds).")
    @PluginProperty(group = "main")
    private final Long lastUpdateTime;

    @Schema(title = "Error message when the job run failed; null otherwise.")
    @PluginProperty(group = "main")
    private final String errorMessage;
}
