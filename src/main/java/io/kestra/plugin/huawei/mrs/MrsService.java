package io.kestra.plugin.huawei.mrs;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.mrs.v1.MrsClient;
import com.huaweicloud.sdk.mrs.v1.model.Cluster;
import com.huaweicloud.sdk.mrs.v1.model.ShowClusterDetailsRequest;
import com.huaweicloud.sdk.mrs.v2.model.JobExecution;
import com.huaweicloud.sdk.mrs.v2.model.JobQueryBean;
import com.huaweicloud.sdk.mrs.v2.model.ShowJobExeListNewRequest;
import com.huaweicloud.sdk.mrs.v2.model.ShowSingleJobExeRequest;
import com.huaweicloud.sdk.mrs.v2.model.StopJobRequest;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.mrs.models.JobConfig;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

// com.huaweicloud.sdk.mrs.v2.MrsClient is referenced by fully-qualified name throughout this class
// to disambiguate it from the v1 MrsClient imported above — MRS has no single client covering both
// cluster (v1) and job-execution (v2) endpoints, see AbstractMrs.

/**
 * Static helpers for MRS cluster/job status polling shared between {@link CreateClusterAndSubmitJob}
 * and {@link SubmitJob}, deliberately free of RunContext so the polling loop stays testable in
 * isolation from the Kestra runtime.
 */
final class MrsService {

    // clusterState enumeration per the authoritative MRS V1 showClusterDetails docs:
    // https://support.huaweicloud.com/intl/en-us/api-mrs/mrs_02_0031.html — starting, running,
    // terminated, failed, abnormal, terminating, frozen, scaling-out, scaling-in.
    // `starting`/`terminating`/`scaling-out`/`scaling-in` are intentionally left out of both sets below:
    // they are transient and expected to progress on their own, so the poll loop must keep waiting
    // through them. `abnormal` (cluster came up unhealthy) and `frozen` (e.g. billing arrears) will
    // never reach `running` by themselves and must be treated as terminal failures, not polled forever.
    private static final Set<String> CLUSTER_FAILURE_STATES = Set.of("failed", "terminated", "abnormal", "frozen");
    private static final String CLUSTER_SUCCESS_STATE = "running";

    // job_state follows the YARN application state machine (New/NEW_SAVING/SUBMITTED/ACCEPTED/RUNNING
    // non-terminal; FINISHED/FAILED/KILLED terminal) — it is NOT a job-level success/failure indicator.
    // YARN can mark an application FINISHED even though it failed at the application level, so the
    // actual outcome must be read from job_result (SUCCEEDED/FAILED/KILLED/UNDEFINED) once job_state
    // is terminal, not inferred from job_state alone.
    private static final Set<String> JOB_TERMINAL_STATES = Set.of("FINISHED", "FAILED", "KILLED");
    private static final Set<String> JOB_FAILURE_STATES = Set.of("FAILED", "KILLED");
    private static final Set<String> JOB_RESULT_FAILURE_VALUES = Set.of("FAILED", "KILLED");

    private MrsService() {
    }

    /**
     * Builds a {@link JobExecution} request body from a rendered {@link JobConfig}, shared by
     * {@link CreateClusterAndSubmitJob} (one call per {@code steps} entry) and {@link SubmitJob} (its
     * single {@code job}) so the same jobType/jobName/arguments/properties mapping isn't duplicated
     * per task.
     */
    static JobExecution toJobExecution(RunContext runContext, JobConfig config, String fieldPrefix) throws Exception {
        var rJobType = runContext.render(config.getJobType()).as(JobType.class)
            .orElseThrow(() -> new IllegalArgumentException(fieldPrefix + ".jobType is required"));
        var rJobName = runContext.render(config.getJobName()).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(fieldPrefix + ".jobName is required"));
        var rArguments = runContext.render(config.getArguments()).asList(String.class);
        var rProperties = runContext.render(config.getProperties()).asMap(String.class, String.class);

        var jobExecution = new JobExecution().withJobType(rJobType.getValue()).withJobName(rJobName);
        if (!rArguments.isEmpty()) {
            jobExecution.withArguments(rArguments);
        }
        if (!rProperties.isEmpty()) {
            jobExecution.withProperties(rProperties);
        }
        return jobExecution;
    }

    /**
     * Polls cluster status until it reaches `running` (success) or a known failure state, bounded by
     * {@code maxDuration}. Throws with an actionable message on timeout or failure state; the cluster
     * itself is not automatically deleted since it is a long-lived infrastructure resource, not an
     * ephemeral job owned by this task.
     */
    static Cluster pollClusterUntilTerminal(
        MrsClient v1Client, String clusterId, Duration interval, Duration maxDuration, Logger logger
    ) throws InterruptedException {
        var deadline = System.currentTimeMillis() + maxDuration.toMillis();
        var cluster = showCluster(v1Client, clusterId);
        var lastLoggedState = cluster.getClusterState();
        logger.debug("MRS cluster '{}' state={}", clusterId, lastLoggedState);

        while (!isClusterTerminal(cluster.getClusterState())) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "MRS cluster '" + clusterId + "' did not reach the 'running' state within " + maxDuration +
                    " — current state: " + cluster.getClusterState() +
                    ". The cluster keeps being created on MRS and is not automatically deleted; " +
                    "increase 'maxDuration' or check the MRS console if it appears stuck.");
            }
            Thread.sleep(interval.toMillis());
            cluster = showCluster(v1Client, clusterId);
            // Only log on a state transition — polling at a fixed interval otherwise floods DEBUG logs
            // with dozens of identical lines while the cluster sits in a single state for a while.
            if (!Objects.equals(cluster.getClusterState(), lastLoggedState)) {
                lastLoggedState = cluster.getClusterState();
                logger.debug("MRS cluster '{}' state={}", clusterId, lastLoggedState);
            }
        }

        if (isClusterFailure(cluster.getClusterState())) {
            throw new IllegalStateException(
                "MRS cluster '" + clusterId + "' finished with state '" + cluster.getClusterState() + "'" +
                (cluster.getStageDesc() != null ? ": " + cluster.getStageDesc() : "") +
                " — check the MRS console for details.");
        }

        return cluster;
    }

    private static Cluster showCluster(MrsClient v1Client, String clusterId) {
        try {
            return v1Client.showClusterDetails(new ShowClusterDetailsRequest().withClusterId(clusterId)).getCluster();
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "Failed to fetch MRS cluster '" + clusterId + "' status (HTTP " + e.getHttpStatusCode() + "): " +
                e.getErrorMsg(), e);
        } catch (SdkException e) {
            throw new IllegalStateException(
                "Failed to fetch MRS cluster '" + clusterId + "' status: " + e.getMessage(), e);
        }
    }

    private static boolean isClusterTerminal(String state) {
        return state != null && (state.equalsIgnoreCase(CLUSTER_SUCCESS_STATE) || isClusterFailure(state));
    }

    private static boolean isClusterFailure(String state) {
        return state != null && CLUSTER_FAILURE_STATES.contains(state.toLowerCase());
    }

    /**
     * Best-effort resolution of the job IDs MRS assigned to the steps submitted at cluster creation
     * time. `RunJobFlow` only returns the new `clusterId`, so step job IDs must be looked up
     * separately once the cluster (and therefore its job-execution list) exists. Matches are made by
     * job name and a submission-time watermark; a step whose job is not yet visible is left absent
     * from the result rather than failing the task, since cluster creation itself already succeeded.
     *
     * <p>{@code submittedTimeBegin} is passed through as epoch milliseconds, matching the other
     * {@code Long} timestamp fields on {@link JobQueryBean} ({@code startedTime}/{@code submittedTime}/
     * {@code finishedTime}); this unit is not verified against a live cluster. If MRS actually expects
     * epoch seconds here, the watermark filter would simply never match and this call would keep
     * returning an empty job list — already handled as a best-effort no-op, not an error.
     */
    static List<String> resolveStepJobIds(
        com.huaweicloud.sdk.mrs.v2.MrsClient v2Client, String clusterId, List<String> stepJobNames, long submittedTimeBegin, Logger logger
    ) {
        List<JobQueryBean> jobs;
        try {
            jobs = v2Client.showJobExeListNew(new ShowJobExeListNewRequest()
                .withClusterId(clusterId)
                .withSubmittedTimeBegin(submittedTimeBegin)
            ).getJobList();
        } catch (SdkException e) {
            logger.warn("Could not resolve job IDs for cluster '{}' steps: {}", clusterId, e.getMessage());
            return List.of();
        }
        if (jobs == null) {
            jobs = List.of();
        }

        var unresolved = new ArrayList<String>();
        var resolved = new ArrayList<String>(stepJobNames.size());
        for (var name : stepJobNames) {
            var jobId = jobs.stream()
                .filter(j -> name.equals(j.getJobName()))
                .map(JobQueryBean::getJobId)
                .findFirst()
                .orElse(null);
            if (jobId != null) {
                resolved.add(jobId);
            } else {
                unresolved.add(name);
            }
        }

        if (!unresolved.isEmpty()) {
            logger.warn(
                "Could not resolve job IDs for {}/{} MRS step(s) on cluster '{}': {}",
                unresolved.size(), stepJobNames.size(), clusterId, unresolved);
        }

        return resolved;
    }

    /**
     * Polls a single job execution until its {@code job_state} reaches a terminal YARN state
     * (`FINISHED`, `FAILED`, `KILLED`), bounded by {@code maxDuration}.
     */
    static JobQueryBean pollJobUntilTerminal(
        com.huaweicloud.sdk.mrs.v2.MrsClient v2Client, String clusterId, String jobExecutionId,
        Duration interval, Duration maxDuration, Logger logger
    ) throws InterruptedException {
        var deadline = System.currentTimeMillis() + maxDuration.toMillis();
        var job = showJob(v2Client, clusterId, jobExecutionId);
        var lastLoggedState = job.getJobState();
        logger.debug("MRS job '{}' state={}", jobExecutionId, lastLoggedState);

        while (!isJobTerminal(job.getJobState())) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "MRS job '" + jobExecutionId + "' on cluster '" + clusterId + "' did not reach a terminal state " +
                    "within " + maxDuration + " — current state: " + job.getJobState() +
                    ". The job keeps running on MRS and is not automatically cancelled; " +
                    "increase 'maxDuration' or cancel it manually if it is stuck.");
            }
            Thread.sleep(interval.toMillis());
            job = showJob(v2Client, clusterId, jobExecutionId);
            // Only log on a state transition — polling at a fixed interval otherwise floods DEBUG logs
            // with dozens of identical lines while the job sits in ACCEPTED/RUNNING.
            if (!Objects.equals(job.getJobState(), lastLoggedState)) {
                lastLoggedState = job.getJobState();
                logger.debug("MRS job '{}' state={}", jobExecutionId, lastLoggedState);
            }
        }

        if (isJobFailure(job)) {
            throw new IllegalStateException(
                "MRS job '" + jobExecutionId + "' on cluster '" + clusterId + "' finished with state '" +
                job.getJobState() + "'" + (job.getJobResult() != null ? ": " + job.getJobResult() : "") +
                " — check the MRS console job history for details.");
        }

        return job;
    }

    private static JobQueryBean showJob(com.huaweicloud.sdk.mrs.v2.MrsClient v2Client, String clusterId, String jobExecutionId) {
        try {
            return v2Client.showSingleJobExe(new ShowSingleJobExeRequest()
                .withClusterId(clusterId)
                .withJobExecutionId(jobExecutionId)
            ).getJobDetail();
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "Failed to fetch MRS job '" + jobExecutionId + "' status (HTTP " + e.getHttpStatusCode() + "): " +
                e.getErrorMsg(), e);
        } catch (SdkException e) {
            throw new IllegalStateException(
                "Failed to fetch MRS job '" + jobExecutionId + "' status: " + e.getMessage(), e);
        }
    }

    private static boolean isJobTerminal(String state) {
        return state != null && JOB_TERMINAL_STATES.contains(state.toUpperCase());
    }

    private static boolean isJobFailure(JobQueryBean job) {
        var state = job.getJobState();
        if (state == null) {
            return false;
        }
        if (JOB_FAILURE_STATES.contains(state.toUpperCase())) {
            return true;
        }
        // job_state=FINISHED only means the YARN application terminated; the application-level outcome
        // (job_result) can still be FAILED/KILLED, which is the actual signal for a job that ran to
        // completion but failed.
        var result = job.getJobResult();
        return result != null && JOB_RESULT_FAILURE_VALUES.contains(result.toUpperCase());
    }

    /** Best-effort job cancellation for {@code kill()} — never throws, logs and returns instead. */
    static void cancelJobQuietly(com.huaweicloud.sdk.mrs.v2.MrsClient v2Client, String clusterId, String jobExecutionId, Logger logger) {
        try {
            v2Client.stopJob(new StopJobRequest().withClusterId(clusterId).withJobExecutionId(jobExecutionId));
            logger.info("Cancelled MRS job '{}' on cluster '{}'", jobExecutionId, clusterId);
        } catch (SdkException e) {
            logger.warn("Failed to cancel MRS job '{}' on cluster '{}': {}", jobExecutionId, clusterId, e.getMessage());
        }
    }
}
