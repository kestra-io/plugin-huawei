package io.kestra.plugin.huawei.dataarts;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Stop an in-progress DataArts Factory job run",
    description = """
        Stops a running DataArts Studio (DataArts Factory) job run instance by calling the
        `POST /v1/{project_id}/jobs/{job_name}/instances/{instance_id}/stop` API.

        When `wait` is `true` (the default), the task polls until the instance status transitions to
        `manual-stop` or another terminal state before returning. `maxDuration` bounds the polling
        time to prevent indefinite hangs — the task fails with a timeout error if the stop is not
        confirmed within the deadline.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Stop a specific job run and wait for confirmation.",
            full = true,
            code = """
                id: dataarts_stop_job
                namespace: company.team

                tasks:
                  - id: stop_run
                    type: io.kestra.plugin.huawei.dataarts.StopJobRun
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    jobName: my_etl_job
                    instanceId: "{{ outputs.start_job.instanceId }}"
                    maxDuration: PT10M
                """
        )
    }
)
public class StopJobRun extends AbstractDataArts implements RunnableTask<StopJobRun.Output> {

    @Schema(
        title = "Name of the DataArts Factory job",
        description = "Must match the job name exactly as defined in the DataArts Studio console."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> jobName;

    @Schema(
        title = "Job run instance ID to stop",
        description = """
            The numeric instance ID of the run to stop. Obtain from the `instanceId` output of
            `StartJobRun` or `GetJobRun`.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Long> instanceId;

    @Schema(
        title = "Wait for the instance to reach a terminal state after stopping",
        description = """
            When `true` (the default), polls the instance status until it transitions to `manual-stop`
            or another terminal state. Set to `false` to return immediately after the stop request is
            accepted.
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Boolean> wait = Property.ofValue(true);

    @Schema(
        title = "Maximum time to wait for the stop to be confirmed",
        description = """
            ISO-8601 duration (e.g. `PT10M`, `PT1H`). When the deadline is reached before the
            instance reaches a terminal state, the task fails with a timeout error. Only relevant
            when `wait` is `true`. Defaults to 10 minutes.
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration = Property.ofValue(Duration.ofMinutes(10));

    @Schema(
        title = "Polling interval while waiting for the stop to complete",
        description = "ISO-8601 duration (e.g. `PT3S`). Defaults to 3 seconds."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Duration> interval = Property.ofValue(Duration.ofSeconds(3));

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rJobName = runContext.render(jobName).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("jobName is required"));
        var rInstanceId = runContext.render(instanceId).as(Long.class).orElseThrow(
            () -> new IllegalArgumentException("instanceId is required"));
        var rProjectId = resolvedProjectId(runContext);
        var rEndpoint = resolvedEndpoint(runContext);
        var rWorkspaceId = resolvedWorkspaceId(runContext);
        var rWait = runContext.render(wait).as(Boolean.class).orElse(true);
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(Duration.ofMinutes(10));
        var rInterval = runContext.render(interval).as(Duration.class).orElse(Duration.ofSeconds(3));

        var config = huaweiClientConfig(runContext);

        runContext.logger().info("Stopping DataArts Factory job '{}' instance {}", rJobName, rInstanceId);

        DataArtsService.stopInstance(runContext, config, rEndpoint, rProjectId, rWorkspaceId, rJobName, rInstanceId);

        if (!rWait) {
            return Output.builder().jobName(rJobName).instanceId(rInstanceId).status("stopping").build();
        }

        // Poll until the instance confirms the stop, bounded by maxDuration.
        var deadline = System.currentTimeMillis() + rMaxDuration.toMillis();
        var current = DataArtsService.getInstance(config, rEndpoint, rProjectId, rWorkspaceId, rJobName, rInstanceId);
        while (!DataArtsService.isTerminalState(current.getStatus())) {
            runContext.logger().debug("Waiting for job '{}' instance {} to stop, status={}", rJobName, rInstanceId, current.getStatus());
            try {
                Thread.sleep(rInterval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "DataArts Factory job '" + rJobName + "' instance " + rInstanceId +
                    " did not reach a terminal state within " + rMaxDuration +
                    " — current status: " + current.getStatus() +
                    ". Increase maxDuration or check the DataArts Studio console.");
            }
            current = DataArtsService.getInstance(config, rEndpoint, rProjectId, rWorkspaceId, rJobName, rInstanceId);
        }

        runContext.logger().info("Job '{}' instance {} stopped, final status={}", rJobName, rInstanceId, current.getStatus());

        return Output.builder()
            .jobName(current.getJobName())
            .instanceId(current.getInstanceId())
            .status(current.getStatus())
            .planTime(current.getPlanTime())
            .startTime(current.getStartTime())
            .endTime(current.getEndTime())
            .lastUpdateTime(current.getLastUpdateTime())
            .errorMessage(current.getErrorMessage())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Job name")
        private final String jobName;

        @Schema(title = "Job run instance ID")
        private final Long instanceId;

        @Schema(title = "Final status of the job run after stopping")
        private final String status;

        @Schema(title = "Scheduled plan time (epoch milliseconds)")
        private final Long planTime;

        @Schema(title = "Actual start time (epoch milliseconds)")
        private final Long startTime;

        @Schema(title = "End time (epoch milliseconds)")
        private final Long endTime;

        @Schema(title = "Last update time (epoch milliseconds)")
        private final Long lastUpdateTime;

        @Schema(title = "Error message when the job run failed; null otherwise")
        private final String errorMessage;
    }
}
