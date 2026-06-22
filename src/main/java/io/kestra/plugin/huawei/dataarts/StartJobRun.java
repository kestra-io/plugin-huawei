package io.kestra.plugin.huawei.dataarts;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
import io.kestra.plugin.huawei.dataarts.models.JobRun;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Start a DataArts Studio (DataArts Factory) job run.",
    description = """
        Starts a batch job in Huawei Cloud DataArts Factory (DLF) by calling the
        `POST /v1/{project_id}/jobs/{job_name}/start` API.

        Because the start API returns HTTP 204 with no instance ID, this task immediately queries the
        instance list to resolve the newly created run. If `wait` is `true` (the default), it polls
        until the run reaches a terminal state (`success`, `fail`, `running-exception`, or
        `manual-stop`), then fails the Kestra task if the job run did not succeed.

        Use `GetJobRun` to fetch the status of an in-progress run without waiting, or `StopJobRun`
        to stop a run before it completes.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Start a DataArts Factory job and wait for it to complete.",
            full = true,
            code = """
                id: dataarts_start_job
                namespace: company.team

                tasks:
                  - id: start_job
                    type: io.kestra.plugin.huawei.dataarts.StartJobRun
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    jobName: my_etl_job
                    wait: true
                    maxDuration: PT30M
                """
        ),
        @Example(
            title = "Start a job with custom parameters and fire-and-forget.",
            full = true,
            code = """
                id: dataarts_start_job_params
                namespace: company.team

                tasks:
                  - id: start_job
                    type: io.kestra.plugin.huawei.dataarts.StartJobRun
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    workspaceId: "{{ secret('HUAWEI_WORKSPACE_ID') }}"
                    jobName: my_parameterized_job
                    jobParams:
                      env: production
                      date: "{{ now() | date('yyyy-MM-dd') }}"
                    wait: false
                """
        )
    }
)
public class StartJobRun extends AbstractDataArts implements RunnableTask<StartJobRun.Output> {

    @Schema(
        title = "Name of the DataArts Factory job to start.",
        description = "Must match the job name exactly as defined in the DataArts Studio console."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> jobName;

    @Schema(
        title = "Job parameters to pass at runtime.",
        description = """
            Optional key/value pairs passed to the job as run-time parameters. Each entry maps a
            parameter name to its value string. The job must declare matching parameter definitions.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, String>> jobParams;

    @Schema(
        title = "Optional start date string passed to the DataArts API.",
        description = """
            When set, passed as the `startDate` field in the start request body. The expected format
            depends on the job schedule configuration in DataArts Studio. Leave blank to start the job
            immediately without a date override.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> startDate;

    @Schema(
        title = "Wait for the job run to reach a terminal state.",
        description = """
            When `true` (the default), the task polls the job run status until it reaches `success`,
            `fail`, `running-exception`, or `manual-stop`. The task fails with a descriptive message
            if the run does not succeed. Set to `false` to fire-and-forget and return immediately
            after the run is created.
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Boolean> wait = Property.ofValue(true);

    @Schema(
        title = "Maximum time to wait for the job run to complete.",
        description = """
            ISO-8601 duration (e.g. `PT30M`, `PT1H`). When the deadline is reached before the run
            reaches a terminal state, the task fails with a timeout error. Only relevant when
            `wait` is `true`. Defaults to 1 hour.
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration = Property.ofValue(Duration.ofHours(1));

    @Schema(
        title = "Polling interval while waiting for the job run to complete.",
        description = "ISO-8601 duration (e.g. `PT5S`). Defaults to 5 seconds."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Duration> interval = Property.ofValue(Duration.ofSeconds(5));

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rJobName = runContext.render(jobName).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("jobName is required"));
        var rProjectId = resolvedProjectId(runContext);
        var rEndpoint = resolvedEndpoint(runContext);
        var rWorkspaceId = resolvedWorkspaceId(runContext);
        var rWait = runContext.render(wait).as(Boolean.class).orElse(true);
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(Duration.ofHours(1));
        var rInterval = runContext.render(interval).as(Duration.class).orElse(Duration.ofSeconds(5));
        var rJobParams = runContext.render(jobParams).asMap(String.class, String.class);
        var rStartDate = runContext.render(startDate).as(String.class).orElse(null);

        var config = huaweiClientConfig(runContext);

        runContext.logger().info("Starting DataArts Factory job '{}' in project '{}'", rJobName, rProjectId);

        // Snapshot the highest known instanceId before triggering the start so resolution can
        // skip stale prior runs that sort newer than the one we're about to create.
        var preStartInstances = DataArtsService.listInstancesFirstPage(
            config, rEndpoint, rProjectId, rWorkspaceId, rJobName, 10);
        var waterMark = preStartInstances.stream()
            .map(r -> r.getInstanceId() != null ? r.getInstanceId() : 0L)
            .max(Long::compare)
            .orElse(0L);

        DataArtsService.startJob(
            runContext, config, rEndpoint, rProjectId, rWorkspaceId,
            rJobName, rJobParams.isEmpty() ? null : rJobParams, rStartDate);

        // Resolve the new instance — the start API returns 204 with no body.
        var instance = resolveNewestInstance(
            runContext, config, rEndpoint, rProjectId, rWorkspaceId, rJobName, rInterval, waterMark);

        runContext.logger().info("Job '{}' started, instanceId={}, status={}", rJobName, instance.getInstanceId(), instance.getStatus());

        if (!rWait) {
            return buildOutput(instance);
        }

        // Poll until terminal or timeout.
        var deadline = System.currentTimeMillis() + rMaxDuration.toMillis();
        var current = instance;

        while (!DataArtsService.isTerminalState(current.getStatus())) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "DataArts Factory job '" + rJobName + "' (instance " + current.getInstanceId() +
                    ") did not reach a terminal state within " + rMaxDuration +
                    " — current status: " + current.getStatus() +
                    ". Use StopJobRun to cancel it, or increase maxDuration.");
            }
            Thread.sleep(rInterval.toMillis());
            current = DataArtsService.getInstance(
                config, rEndpoint, rProjectId, rWorkspaceId, rJobName, current.getInstanceId());
            runContext.logger().debug("Job '{}' instanceId={} status={}", rJobName, current.getInstanceId(), current.getStatus());
        }

        runContext.logger().info("Job '{}' instanceId={} finished with status={}", rJobName, current.getInstanceId(), current.getStatus());

        if (!DataArtsService.isSuccessState(current.getStatus())) {
            throw new IllegalStateException(
                "DataArts Factory job '" + rJobName + "' (instance " + current.getInstanceId() +
                ") finished with status '" + current.getStatus() + "'" +
                (current.getErrorMessage() != null ? ": " + current.getErrorMessage() : "") +
                " — check the DataArts Studio job logs for details.");
        }

        return buildOutput(current);
    }

    /**
     * Polls the instance list until an instance with instanceId > waterMark appears.
     * The water mark is snapshotted before the start call to avoid picking up a recent prior run
     * whose planTime/startTime could sort newer than the one just triggered.
     */
    private JobRun resolveNewestInstance(
        RunContext runContext,
        AbstractConnection.HuaweiClientConfig config,
        String endpoint, String projectId, String workspaceId,
        String jobName, Duration interval, long waterMark
    ) throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            var instances = DataArtsService.listInstancesFirstPage(config, endpoint, projectId, workspaceId, jobName, 10);
            var newInstance = instances.stream()
                .filter(r -> r.getInstanceId() != null && r.getInstanceId() > waterMark)
                .findFirst();
            if (newInstance.isPresent()) {
                return newInstance.get();
            }
            runContext.logger().debug("New instance not yet visible for job '{}' (waterMark={}), waiting {} (attempt {}/10)", jobName, waterMark, interval, attempt + 1);
            Thread.sleep(interval.toMillis());
        }
        throw new IllegalStateException(
            "DataArts Factory job '" + jobName + "' was started but no new instance appeared within the expected time — " +
            "the job may have been queued but not yet scheduled. Try increasing the polling interval.");
    }

    private static Output buildOutput(JobRun run) {
        return Output.builder()
            .jobName(run.getJobName())
            .instanceId(run.getInstanceId())
            .status(run.getStatus())
            .planTime(run.getPlanTime())
            .startTime(run.getStartTime())
            .endTime(run.getEndTime())
            .lastUpdateTime(run.getLastUpdateTime())
            .errorMessage(run.getErrorMessage())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Job name.")
        @PluginProperty(group = "main")
        private final String jobName;

        @Schema(title = "Job run instance ID.")
        @PluginProperty(group = "main")
        private final Long instanceId;

        @Schema(title = "Terminal status of the job run.")
        @PluginProperty(group = "main")
        private final String status;

        @Schema(title = "Scheduled plan time (epoch milliseconds).")
        @PluginProperty(group = "main")
        private final Long planTime;

        @Schema(title = "Actual start time (epoch milliseconds).")
        @PluginProperty(group = "main")
        private final Long startTime;

        @Schema(title = "End time (epoch milliseconds).")
        @PluginProperty(group = "main")
        private final Long endTime;

        @Schema(title = "Last update time (epoch milliseconds).")
        @PluginProperty(group = "main")
        private final Long lastUpdateTime;

        @Schema(title = "Error message when the job run failed; null otherwise.")
        @PluginProperty(group = "main")
        private final String errorMessage;
    }
}
