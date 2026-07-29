package io.kestra.plugin.huawei.dataarts.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;

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
            is not displayed anywhere in the console and has no API of its own, so the only way to
            obtain one is `GetJobRun` with `instanceId` omitted, which resolves the latest run.
            `StartJobRun` does not return one: it identifies its supplement-data run by `runName`."""
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

    @Schema(title = "Scheduled plan time")
    private final Instant planTime;

    @Schema(title = "Actual start time")
    private final Instant startTime;

    @Schema(title = "End time; null if still running")
    private final Instant endTime;

    @Schema(
        title = "How long the instance took to execute",
        description = """
            Elapsed run time, not a timestamp, despite the API naming the field `execute_time`:
            a live instance reported `4000` alongside an `end_time` exactly 4000 ms after its
            `start_time`."""
    )
    private final Duration executeTime;

    @Schema(title = "Submission time")
    private final Instant submitTime;

    @Schema(title = "ID of the job this instance belongs to")
    private final Long jobId;
}
