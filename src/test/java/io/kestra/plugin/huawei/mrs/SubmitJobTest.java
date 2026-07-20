package io.kestra.plugin.huawei.mrs;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.huawei.mrs.models.JobConfig;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubmitJobTest {

    private static final String PROJECT_ID = "test-project-abc123";
    private static final String FAKE_AK = "FAKEACCESSKEY0001";
    private static final String FAKE_SK = "fakeSecretKey0001fakeSecretKey001";
    private static final String CLUSTER_ID = "cluster-001";
    private static final String JOB_EXECUTION_ID = "job-exec-001";

    private WireMockServer wireMock;

    @Inject
    RunContextFactory runContextFactory;

    @BeforeAll
    void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @AfterAll
    void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    private String wireMockUrl() {
        return "http://localhost:" + wireMock.port();
    }

    private SubmitJob.SubmitJobBuilder<?, ?> baseTask() {
        return SubmitJob.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .clusterId(Property.ofValue(CLUSTER_ID))
            .job(Property.ofValue(JobConfig.builder()
                .jobType(Property.ofValue(JobType.HIVE_SCRIPT))
                .jobName(Property.ofValue("cleanup-staging"))
                .arguments(Property.ofValue(List.of("obs://my-bucket/scripts/cleanup.sql")))
                .build()))
            .interval(Property.ofValue(Duration.ofMillis(20)));
    }

    private void stubCreateExecuteJob() {
        wireMock.stubFor(post(urlMatching(".*/clusters/" + CLUSTER_ID + "/job-executions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"job_submit_result": {"job_id": "%s", "state": "COMPLETE"}}
                    """.formatted(JOB_EXECUTION_ID))));
    }

    private void stubJobState(String state) {
        wireMock.stubFor(get(urlMatching(".*/clusters/" + CLUSTER_ID + "/job-executions/" + JOB_EXECUTION_ID))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"job_detail": {"job_id": "%s", "job_state": "%s", "job_result": "some detail"}}
                    """.formatted(JOB_EXECUTION_ID, state))));
    }

    // ── Happy path ───────────────────────────────────────────────────────────────

    @Test
    void submitJob_waitTrue_returnsTerminalState() throws Exception {
        stubCreateExecuteJob();
        stubJobState("COMPLETED");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().build();

        var output = task.run(runContext);

        assertThat(output.getJobId(), equalTo(JOB_EXECUTION_ID));
        assertThat(output.getJobState(), equalTo("COMPLETED"));
    }

    @Test
    void submitJob_waitFalse_returnsImmediately() throws Exception {
        stubCreateExecuteJob();
        // No job-state stub registered — a poll call would fail the test.

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().wait(Property.ofValue(false)).build();

        var output = task.run(runContext);

        assertThat(output.getJobId(), equalTo(JOB_EXECUTION_ID));
        assertThat(output.getJobState(), equalTo(null));
    }

    // ── Failure / timeout ────────────────────────────────────────────────────────

    @Test
    void submitJob_jobFails_throwsActionableError() {
        stubCreateExecuteJob();
        stubJobState("FAILED");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("FAILED"));
        assertThat(ex.getMessage(), containsString(JOB_EXECUTION_ID));
    }

    @Test
    void submitJob_timeout_throwsActionableError() {
        stubCreateExecuteJob();
        stubJobState("RUNNING");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .maxDuration(Property.ofValue(Duration.ofMillis(150)))
            .interval(Property.ofValue(Duration.ofMillis(20)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString(JOB_EXECUTION_ID));
        assertThat(ex.getMessage(), containsString("terminal"));
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    @Test
    void submitJob_customEndpointWithoutProjectId_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().projectId(null).build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("projectId"));
    }

    // ── kill() ───────────────────────────────────────────────────────────────────

    @Test
    void kill_beforeSubmit_isNoOp() {
        var task = SubmitJob.builder().build();
        assertDoesNotThrow(task::kill);
    }

    @Test
    void kill_afterSubmit_cancelsRemoteJob() throws Exception {
        stubCreateExecuteJob();
        stubJobState("COMPLETED");
        wireMock.stubFor(post(urlMatching(".*/job-executions/" + JOB_EXECUTION_ID + "/kill"))
            .willReturn(aResponse().withStatus(200).withBody("{}")));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().wait(Property.ofValue(false)).build();

        task.run(runContext);
        task.kill();

        wireMock.verify(WireMock.postRequestedFor(urlMatching(".*/job-executions/" + JOB_EXECUTION_ID + "/kill")));
    }

    // ── Integration test (guarded) ────────────────────────────────────────────────
    // Requires a pre-existing, running MRS cluster (MRS_TEST_CLUSTER_ID) — cluster creation/deletion
    // is too slow and costly to exercise on every gated run; CreateClusterAndSubmitJob and
    // DeleteCluster share the same connection/endpoint-resolution code path and are covered above.

    @Test
    @EnabledIfEnvironmentVariable(named = "MRS_TESTS", matches = "true")
    void submitJob_realCloud_happyPath() throws Exception {
        var region = System.getenv("MRS_REGION");
        var ak = System.getenv("HUAWEI_ACCESS_KEY");
        var sk = System.getenv("HUAWEI_SECRET_ACCESS_KEY");
        var projectId = System.getenv("HUAWEI_PROJECT_ID");
        var testClusterId = System.getenv("MRS_TEST_CLUSTER_ID");

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = SubmitJob.builder()
            .accessKeyId(Property.ofValue(ak))
            .secretAccessKey(Property.ofValue(sk))
            .region(Property.ofValue(region))
            .projectId(Property.ofValue(projectId))
            .clusterId(Property.ofValue(testClusterId))
            .job(Property.ofValue(JobConfig.builder()
                .jobType(Property.ofValue(JobType.HIVE_SCRIPT))
                .jobName(Property.ofValue("kestra-it-" + System.currentTimeMillis()))
                .arguments(Property.ofValue(List.of("SELECT 1;")))
                .build()))
            .maxDuration(Property.ofValue(Duration.ofMinutes(10)))
            .build();

        var output = task.run(runContext);

        assertThat(output.getJobId(), org.hamcrest.Matchers.notNullValue());
        assertThat(output.getJobState(), equalTo("COMPLETED"));
    }
}
