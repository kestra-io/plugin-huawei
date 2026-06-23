package io.kestra.plugin.huawei.dataarts;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.dataarts.models.JobRun;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
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
@Schema(
    title = "Fetch the status and metadata of a DataArts Factory job run.",
    description = """
        Retrieves the current status of a DataArts Studio (DataArts Factory) job run instance.

        When `instanceId` is provided, the specific instance is fetched directly. When omitted, the
        most recently created instance for the job is returned (resolved by querying the instance
        list and selecting the newest entry by plan/start time).

        This task performs a single fetch without polling. To wait for completion, use `StartJobRun`
        with `wait: true`.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Get the latest run of a job.",
            full = true,
            code = """
                id: dataarts_get_job_run
                namespace: company.team

                tasks:
                  - id: get_run
                    type: io.kestra.plugin.huawei.dataarts.GetJobRun
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    jobName: my_etl_job
                """
        ),
        @Example(
            title = "Get a specific job run by instance ID.",
            full = true,
            code = """
                id: dataarts_get_specific_run
                namespace: company.team

                tasks:
                  - id: get_run
                    type: io.kestra.plugin.huawei.dataarts.GetJobRun
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    jobName: my_etl_job
                    instanceId: "{{ outputs.start_job.instanceId }}"
                """
        )
    }
)
public class GetJobRun extends AbstractDataArts implements RunnableTask<GetJobRun.Output> {

    @Schema(
        title = "Name of the DataArts Factory job.",
        description = "Must match the job name exactly as defined in the DataArts Studio console."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> jobName;

    @Schema(
        title = "Job run instance ID to fetch.",
        description = """
            When set, fetches the specific instance by ID. When omitted, the most recently started
            instance for `jobName` is returned. Use the `instanceId` from a previous `StartJobRun`
            output to track a specific run.
            """
    )
    @PluginProperty(group = "main")
    private Property<Long> instanceId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rJobName = runContext.render(jobName).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("jobName is required"));
        var rProjectId = resolvedProjectId(runContext);
        var rEndpoint = resolvedEndpoint(runContext);
        var rWorkspaceId = resolvedWorkspaceId(runContext);
        var rInstanceId = runContext.render(instanceId).as(Long.class).orElse(null);

        var config = huaweiClientConfig(runContext);

        JobRun run;
        if (rInstanceId != null) {
            runContext.logger().debug("Fetching instance {} for job '{}'", rInstanceId, rJobName);
            run = DataArtsService.getInstance(config, rEndpoint, rProjectId, rWorkspaceId, rJobName, rInstanceId);
        } else {
            runContext.logger().debug("Resolving latest instance for job '{}'", rJobName);
            var instances = DataArtsService.listInstancesFirstPage(config, rEndpoint, rProjectId, rWorkspaceId, rJobName, 1);
            if (instances.isEmpty()) {
                throw new IllegalStateException(
                    "No job run instances found for job '" + rJobName +
                    "' — the job may not have been started yet, or all instances have been purged.");
            }
            run = instances.getFirst();
        }

        runContext.logger().info("Job '{}' instanceId={} status={}", rJobName, run.getInstanceId(), run.getStatus());

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
        private final String jobName;

        @Schema(title = "Job run instance ID.")
        private final Long instanceId;

        @Schema(title = "Current status of the job run.")
        private final String status;

        @Schema(title = "Scheduled plan time (epoch milliseconds).")
        private final Long planTime;

        @Schema(title = "Actual start time (epoch milliseconds).")
        private final Long startTime;

        @Schema(title = "End time (epoch milliseconds); null if still running.")
        private final Long endTime;

        @Schema(title = "Last update time (epoch milliseconds).")
        private final Long lastUpdateTime;

        @Schema(title = "Error message when the job run failed; null otherwise.")
        private final String errorMessage;
    }
}
