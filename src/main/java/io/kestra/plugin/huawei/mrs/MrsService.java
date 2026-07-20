package io.kestra.plugin.huawei.mrs;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.mrs.v1.MrsClient;
import com.huaweicloud.sdk.mrs.v1.model.Cluster;
import com.huaweicloud.sdk.mrs.v1.model.ShowClusterDetailsRequest;
import com.huaweicloud.sdk.mrs.v2.model.JobQueryBean;
import com.huaweicloud.sdk.mrs.v2.model.ShowJobExeListNewRequest;
import com.huaweicloud.sdk.mrs.v2.model.ShowSingleJobExeRequest;
import com.huaweicloud.sdk.mrs.v2.model.StopJobRequest;
import org.slf4j.Logger;

import java.time.Duration;
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

    private static final Set<String> CLUSTER_FAILURE_STATES = Set.of("failed", "bootstrap_fail", "terminated");
    private static final String CLUSTER_SUCCESS_STATE = "running";

    private static final Set<String> JOB_FAILURE_STATES = Set.of("FAILED", "ABNORMAL", "TERMINATED", "KILLED");
    private static final String JOB_SUCCESS_STATE = "COMPLETED";

    private MrsService() {
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
            logger.debug("MRS cluster '{}' state={}", clusterId, cluster.getClusterState());
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
            return List.of();
        }
        return stepJobNames.stream()
            .map(name -> jobs.stream()
                .filter(j -> name.equals(j.getJobName()))
                .map(JobQueryBean::getJobId)
                .findFirst()
                .orElse(null))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Polls a single job execution until it reaches `COMPLETED` (success) or a known failure state,
     * bounded by {@code maxDuration}.
     */
    static JobQueryBean pollJobUntilTerminal(
        com.huaweicloud.sdk.mrs.v2.MrsClient v2Client, String clusterId, String jobExecutionId,
        Duration interval, Duration maxDuration, Logger logger
    ) throws InterruptedException {
        var deadline = System.currentTimeMillis() + maxDuration.toMillis();
        var job = showJob(v2Client, clusterId, jobExecutionId);

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
            logger.debug("MRS job '{}' state={}", jobExecutionId, job.getJobState());
        }

        if (isJobFailure(job.getJobState())) {
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
        return state != null && (JOB_SUCCESS_STATE.equalsIgnoreCase(state) || isJobFailure(state));
    }

    private static boolean isJobFailure(String state) {
        return state != null && JOB_FAILURE_STATES.contains(state.toUpperCase());
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
