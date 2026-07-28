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
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Stop an in-progress DataArts Factory job run",
    description = """
        Cancels a running supplement-data (PatchData) run — one created by `StartJobRun` — via
        `POST /v2/{project_id}/factory/supplement-data/{instance_name}/stop`.

        **Scope**: this is the only stop route DataArts Factory publishes. There is no API to stop a
        plain job instance, so a run triggered from the DataArts Studio console cannot be stopped
        from Kestra — only one created by `StartJobRun`. Identify the run by the `runName` that task
        returned.

        When `wait` is `true` (the default), the task polls until the run reaches a terminal state
        before returning. `maxDuration` bounds the polling so a run that never confirms the stop
        fails with a timeout rather than hanging.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Stop a run started earlier in the same flow.",
            full = true,
            code = """
                id: dataarts_stop_job
                namespace: company.team

                tasks:
                  - id: start_run
                    type: io.kestra.plugin.huawei.dataarts.StartJobRun
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    workspaceId: "{{ secret('HUAWEI_WORKSPACE_ID') }}"
                    jobName: my_etl_job
                    wait: false

                  - id: stop_run
                    type: io.kestra.plugin.huawei.dataarts.StopJobRun
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    workspaceId: "{{ secret('HUAWEI_WORKSPACE_ID') }}"
                    runName: "{{ outputs.start_run.runName }}"
                    maxDuration: PT10M
                """
        )
    }
)
public class StopJobRun extends AbstractDataArts implements RunnableTask<StopJobRun.Output> {

    @Schema(
        title = "Name of the supplement-data run to stop",
        description = "The `runName` output of `StartJobRun`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> runName;

    @Schema(
        title = "Wait for the run to reach a terminal state after stopping",
        description = """
            When `true` (the default), polls the run's status until it stops. Set to `false` to return
            as soon as the stop request is accepted.
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Boolean> wait = Property.ofValue(true);

    @Schema(
        title = "Maximum time to wait for the stop to be confirmed",
        description = """
            ISO-8601 duration (e.g. `PT10M`, `PT1H`). When the deadline is reached before the run
            reaches a terminal state, the task fails with a timeout error. Only relevant when
            `wait` is `true`. Defaults to 10 minutes.
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
        var rRunName = runContext.render(runName).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("runName is required"));
        var rProjectId = resolvedProjectId(runContext);
        var rEndpoint = resolvedEndpoint(runContext);
        var rWorkspaceId = resolvedWorkspaceId(runContext);
        var rWait = runContext.render(wait).as(Boolean.class).orElse(true);
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(Duration.ofMinutes(10));
        var rInterval = runContext.render(interval).as(Duration.class).orElse(Duration.ofSeconds(3));

        var config = huaweiClientConfig(runContext);

        runContext.logger().info("Stopping DataArts Factory supplement-data run '{}'", rRunName);

        DataArtsService.stopSupplementData(runContext, config, rEndpoint, rProjectId, rWorkspaceId, rRunName);

        if (!rWait) {
            return Output.builder().runName(rRunName).status("stopping").build();
        }

        var deadline = System.currentTimeMillis() + rMaxDuration.toMillis();
        var current = DataArtsService.getSupplementData(runContext, config, rEndpoint, rProjectId, rWorkspaceId, rRunName);

        while (current == null || !DataArtsService.isSupplementDataTerminalState(current.getStatus())) {
            try {
                Thread.sleep(rInterval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "DataArts Factory supplement-data run '" + rRunName +
                    "' did not reach a terminal state within " + rMaxDuration +
                    " — last status: " + (current == null ? "unknown" : current.getStatus()) +
                    ". Increase maxDuration or check the DataArts Studio console.");
            }
            var refreshed = DataArtsService.getSupplementData(runContext, config, rEndpoint, rProjectId, rWorkspaceId, rRunName);
            if (refreshed != null) {
                current = refreshed;
            }
            // INFO rather than DEBUG: the supplement-data status vocabulary is undocumented, so these
            // lines are how the real values get discovered from a run's logs.
            runContext.logger().info("Supplement-data run '{}' status={}",
                rRunName, current == null ? "not yet visible" : current.getStatus());
        }

        runContext.logger().info("Supplement-data run '{}' stopped, final status={}", rRunName, current.getStatus());

        return Output.builder()
            .runName(current.getName())
            .status(current.getStatus())
            .jobList(current.getJobList())
            .startDate(current.getStartDate())
            .endDate(current.getEndDate())
            .submittedDate(current.getSubmittedDate())
            .parallel(current.getParallel())
            .userName(current.getUserName())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Name of the supplement-data run")
        private final String runName;

        @Schema(title = "Status of the run after stopping")
        private final String status;

        @Schema(title = "Jobs covered by the run")
        private final List<String> jobList;

        @Schema(title = "Start of the covered business-date range (epoch milliseconds)")
        private final Long startDate;

        @Schema(title = "End of the covered business-date range (epoch milliseconds)")
        private final Long endDate;

        @Schema(title = "Time the run was submitted (epoch milliseconds)")
        private final Long submittedDate;

        @Schema(title = "Number of instances executed in parallel")
        private final Integer parallel;

        @Schema(title = "User the run was submitted as")
        private final String userName;
    }
}
