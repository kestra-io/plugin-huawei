package io.kestra.plugin.huawei.mrs;

import com.huaweicloud.sdk.mrs.v1.MrsClient;
import com.huaweicloud.sdk.mrs.v1.model.Cluster;
import com.huaweicloud.sdk.mrs.v1.model.ShowClusterDetailsResponse;
import com.huaweicloud.sdk.mrs.v2.model.JobQueryBean;
import com.huaweicloud.sdk.mrs.v2.model.ShowSingleJobExeResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link MrsService}'s poll loops directly against a mocked SDK client, without going
 * through a Kestra RunContext — {@link MrsService} is deliberately free of RunContext for exactly
 * this kind of isolated testing (see its class javadoc).
 */
class MrsServiceTest {

    private static final String CLUSTER_ID = "cluster-001";
    private static final String JOB_EXECUTION_ID = "job-exec-001";

    @Test
    void pollJobUntilTerminal_logsOnlyOnStateTransition() throws Exception {
        var v2Client = mock(com.huaweicloud.sdk.mrs.v2.MrsClient.class);
        // A live SparkPi job progression: ACCEPTED (seen twice), RUNNING (seen twice), FINISHED —
        // the exact regression scenario reported from a live cluster.
        when(v2Client.showSingleJobExe(any())).thenReturn(
            jobResponse("ACCEPTED", "some detail"),
            jobResponse("ACCEPTED", "some detail"),
            jobResponse("RUNNING", "some detail"),
            jobResponse("RUNNING", "some detail"),
            jobResponse("FINISHED", "SUCCEEDED")
        );
        var logger = mock(Logger.class);

        var job = MrsService.pollJobUntilTerminal(
            v2Client, CLUSTER_ID, JOB_EXECUTION_ID, Duration.ofMillis(1), Duration.ofSeconds(5), logger);

        assertThat(job.getJobState(), equalTo("FINISHED"));

        var stateCaptor = ArgumentCaptor.forClass(String.class);
        // 5 fetches total (1 initial + 4 loop iterations) but only 3 distinct states — logging on
        // every transition (not every tick) must yield exactly 3 debug lines.
        verify(logger, times(3)).debug(anyString(), eq(JOB_EXECUTION_ID), stateCaptor.capture());
        assertThat(stateCaptor.getAllValues(), contains("ACCEPTED", "RUNNING", "FINISHED"));
    }

    @Test
    void pollClusterUntilTerminal_logsOnlyOnStateTransition() throws Exception {
        var v1Client = mock(MrsClient.class);
        // `starting` is transient (seen twice) before reaching `running`.
        when(v1Client.showClusterDetails(any())).thenReturn(
            clusterResponse("starting"),
            clusterResponse("starting"),
            clusterResponse("running")
        );
        var logger = mock(Logger.class);

        var cluster = MrsService.pollClusterUntilTerminal(
            v1Client, CLUSTER_ID, Duration.ofMillis(1), Duration.ofSeconds(5), logger);

        assertThat(cluster.getClusterState(), equalTo("running"));

        var stateCaptor = ArgumentCaptor.forClass(String.class);
        // 3 fetches total (1 initial + 2 loop iterations) but only 2 distinct states.
        verify(logger, times(2)).debug(anyString(), eq(CLUSTER_ID), stateCaptor.capture());
        assertThat(stateCaptor.getAllValues(), contains("starting", "running"));
    }

    private ShowSingleJobExeResponse jobResponse(String state, String jobResult) {
        return new ShowSingleJobExeResponse().withJobDetail(new JobQueryBean()
            .withJobId(JOB_EXECUTION_ID)
            .withJobState(state)
            .withJobResult(jobResult));
    }

    private ShowClusterDetailsResponse clusterResponse(String state) {
        return new ShowClusterDetailsResponse().withCluster(new Cluster()
            .withClusterId(CLUSTER_ID)
            .withClusterState(state));
    }
}
