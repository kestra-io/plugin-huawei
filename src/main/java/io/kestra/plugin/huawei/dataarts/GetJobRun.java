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

import java.time.Duration;
import java.time.Instant;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Fetch the status and metadata of a DataArts Factory job run",
    description = """
        Retrieves the current status of a DataArts Studio (DataArts Factory) job run instance.

        When `instanceId` is provided, the specific instance is fetched directly. When omitted, the
        most recently created instance for the job is returned (resolved by querying the instance
        list and selecting the newest entry by plan/start time).

        This reports on plain job instances, whatever started them — a schedule, the console, or a
        `StartJobRun` supplement-data run. It is not tied to `StartJobRun`: that task tracks its own
        run by `runName`, and `wait: true` there polls the supplement-data run rather than the
        individual instances it spawns.

        This task performs a single fetch without polling.
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
            title = "Follow one specific job run. The instance ID is not visible in the console and " +
                "has no API of its own, so it is resolved once by a `GetJobRun` with `instanceId` " +
                "omitted, then reused to re-read that same instance rather than whichever run is " +
                "latest at the time.",
            full = true,
            code = """
                id: dataarts_get_specific_run
                namespace: company.team

                tasks:
                  - id: resolve_latest
                    type: io.kestra.plugin.huawei.dataarts.GetJobRun
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    jobName: my_etl_job

                  - id: get_run
                    type: io.kestra.plugin.huawei.dataarts.GetJobRun
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    jobName: my_etl_job
                    instanceId: "{{ outputs.resolve_latest.instanceId }}"
                """
        )
    }
)
public class GetJobRun extends AbstractDataArts implements RunnableTask<GetJobRun.Output> {

    @Schema(
        title = "Name of the DataArts Factory job",
        description = "Must match the job name exactly as defined in the DataArts Studio console."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> jobName;

    @Schema(
        title = "Job run instance ID to fetch",
        description = """
            When set, fetches that specific instance. When omitted, the most recently started instance
            for `jobName` is returned — which is the normal way to use this task.

            The ID is the API's numeric `instance_id`. It is **not** the `instanceId` UUID in the
            DataArts Studio console URL (that identifies the DataArts Studio service instance, an
            unrelated entity), and it is not displayed anywhere in the console. Nor does `StartJobRun`
            return one: that task creates a supplement-data run identified by `runName`, and the job
            instances it spawns are separate entities. So the only source of a value for this property
            is the `instanceId` output of a `GetJobRun` that ran with it omitted.
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
            .jobInstanceName(run.getJobInstanceName())
            .status(run.getStatus())
            .planTime(run.getPlanTime())
            .startTime(run.getStartTime())
            .endTime(run.getEndTime())
            .executeTime(run.getExecuteTime())
            .submitTime(run.getSubmitTime())
            .jobId(run.getJobId())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Job name")
        private final String jobName;

        @Schema(title = "Job run instance ID")
        private final Long instanceId;

        @Schema(title = "Job run instance name, as shown in the console's job monitoring list")
        private final String jobInstanceName;

        @Schema(title = "Current status of the job run")
        private final String status;

        @Schema(title = "Scheduled plan time")
        private final Instant planTime;

        @Schema(title = "Actual start time")
        private final Instant startTime;

        @Schema(title = "End time; null if still running")
        private final Instant endTime;

        @Schema(title = "How long the instance took to execute")
        private final Duration executeTime;

        @Schema(title = "Submission time")
        private final Instant submitTime;

        @Schema(title = "ID of the job this instance belongs to")
        private final Long jobId;
    }
}
