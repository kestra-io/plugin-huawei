package io.kestra.plugin.huawei.dataarts.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobRun {

    @Schema(title = "Job name")
    private final String jobName;

    @Schema(
        title = "Job run instance ID",
        description = """
            Numeric identifier of the job run instance, as returned by the API's `instance_id` field.

            This is **not** the `instanceId` shown in the DataArts Studio console URL — that UUID
            identifies the DataArts Studio service instance, not a job run. The numeric job-run ID
            is not displayed anywhere in the console; obtain it from `StartJobRun`'s output or from
            `GetJobRun` without an `instanceId` (which resolves the latest run)."""
    )
    private final Long instanceId;

    @Schema(title = "Job run instance name, as shown in the console's job monitoring list")
    private final String jobInstanceName;

    @Schema(
        title = "Job run status",
        description = """
            Lifecycle status of the job run instance, as defined by the API's status enum:
            - `waiting` — queued, not yet started.
            - `running` — currently executing.
            - `success` — completed successfully.
            - `fail` — completed with an error.
            - `manual` — awaiting manual confirmation.
            - `pause` — paused by user.
            - `skip` — skipped.
            - `freeze` — frozen.
            """
    )
    private final String status;

    @Schema(title = "Scheduled plan time (epoch milliseconds)")
    private final Long planTime;

    @Schema(title = "Actual start time (epoch milliseconds)")
    private final Long startTime;

    @Schema(title = "End time (epoch milliseconds); null if still running")
    private final Long endTime;

    @Schema(title = "Execution time (epoch milliseconds)")
    private final Long executeTime;

    @Schema(title = "Submission time (epoch milliseconds)")
    private final Long submitTime;

    @Schema(title = "ID of the job this instance belongs to")
    private final Long jobId;
}
