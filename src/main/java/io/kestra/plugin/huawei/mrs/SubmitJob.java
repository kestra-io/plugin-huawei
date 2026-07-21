package io.kestra.plugin.huawei.mrs;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.mrs.v2.model.CreateExecuteJobRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.mrs.models.JobConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Submit a job step to an existing Huawei Cloud MRS cluster",
    description = """
        Submits a single job execution to an already-running MRS cluster via `createExecuteJob` —
        the Huawei Cloud equivalent of `io.kestra.plugin.aws.emr.SubmitSteps`, adapted to MRS's
        one-job-per-call submission API.

        Set `wait` to `true` (the default) to poll the job until it reaches a terminal state
        (`FINISHED`, or a failure state), or `false` to fire-and-forget and return immediately after
        submission.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Submit a Hive script to a running cluster and wait for it to finish.",
            full = true,
            code = """
                id: mrs_submit_job
                namespace: company.team

                tasks:
                  - id: submit_job
                    type: io.kestra.plugin.huawei.mrs.SubmitJob
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    clusterId: "{{ outputs.create_cluster.clusterId }}"
                    job:
                      jobType: HIVE_SCRIPT
                      jobName: cleanup-staging
                      arguments:
                        - "obs://my-bucket/scripts/cleanup.sql"
                    maxDuration: PT20M
                """
        )
    }
)
public class SubmitJob extends AbstractMrs implements RunnableTask<SubmitJob.Output> {

    @Schema(title = "ID of the target cluster", description = "The cluster must already exist and be in the `running` state.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> clusterId;

    @Schema(title = "Job step to submit")
    @NotNull
    @PluginProperty(group = "main")
    private Property<JobConfig> job;

    @Schema(
        title = "Wait for the job to reach a terminal state",
        description = "When `true` (the default), polls the job until `FINISHED` or a failure state. When `false`, returns immediately after submission."
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Boolean> wait = Property.ofValue(true);

    @Schema(title = "Maximum time to wait for the job to complete", description = "ISO-8601 duration. Defaults to 1 hour.")
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Duration> maxDuration = Property.ofValue(Duration.ofHours(1));

    @Schema(title = "Polling interval while waiting for the job", description = "ISO-8601 duration. Defaults to 5 seconds.")
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Duration> interval = Property.ofValue(Duration.ofSeconds(5));

    // Guards against a duplicate/racing kill signal; cancels the remote MRS job so cluster resources
    // stop being consumed by it after the Kestra execution is killed. Excluded from
    // equals/hashCode/toString: AtomicReference/AtomicBoolean use identity equality, so two otherwise-
    // identical task instances would never be equal, and their content is a runtime implementation
    // detail, not task config.
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final AtomicReference<Runnable> killable = new AtomicReference<>();

    @Builder.Default
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final AtomicBoolean isKilled = new AtomicBoolean(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rClusterId = runContext.render(clusterId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("clusterId is required"));
        var rJob = runContext.render(job).as(JobConfig.class)
            .orElseThrow(() -> new IllegalArgumentException("job is required"));
        var rWait = runContext.render(wait).as(Boolean.class).orElse(true);
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(Duration.ofHours(1));
        var rInterval = runContext.render(interval).as(Duration.class).orElse(Duration.ofSeconds(5));

        var jobExecution = MrsService.toJobExecution(runContext, rJob, "job");
        var rJobName = jobExecution.getJobName();

        var client = client(runContext);

        String jobExecutionId;
        try {
            var result = client.createExecuteJob(new CreateExecuteJobRequest()
                .withClusterId(rClusterId)
                .withBody(jobExecution)
            ).getJobSubmitResult();
            jobExecutionId = result.getJobId();
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "Failed to submit MRS job '" + rJobName + "' to cluster '" + rClusterId + "' (HTTP " +
                e.getHttpStatusCode() + "): " + e.getErrorMsg() +
                " — verify the cluster exists and is in the 'running' state.", e);
        } catch (SdkException e) {
            throw new IllegalStateException(
                "Failed to submit MRS job '" + rJobName + "' to cluster '" + rClusterId + "': " + e.getMessage(), e);
        }

        killable.set(() -> MrsService.cancelJobQuietly(client, rClusterId, jobExecutionId, logger));
        // Closes the submit->set race: if kill() ran (and found killable still null) between the job
        // going live on MRS and this line, re-invoke it now against the freshly-set killable.
        // cancelJobQuietly is idempotent, so this is safe even if kill() never actually raced.
        if (isKilled.get()) {
            killable.get().run();
        }

        logger.info("Submitted MRS job '{}' (id={}) to cluster '{}'", rJobName, jobExecutionId, rClusterId);

        if (!rWait) {
            return Output.builder().jobId(jobExecutionId).jobState(null).build();
        }

        var job = MrsService.pollJobUntilTerminal(client, rClusterId, jobExecutionId, rInterval, rMaxDuration, logger);
        logger.info("MRS job '{}' finished with state '{}'", jobExecutionId, job.getJobState());

        return Output.builder().jobId(jobExecutionId).jobState(job.getJobState()).build();
    }

    @Override
    public void kill() {
        if (isKilled.compareAndSet(false, true)) {
            Optional.ofNullable(killable.get()).ifPresent(Runnable::run);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "MRS job execution ID")
        private final String jobId;

        @Schema(title = "Terminal job state", description = "`FINISHED` on success. `null` when `wait` is `false`.")
        private final String jobState;
    }
}
