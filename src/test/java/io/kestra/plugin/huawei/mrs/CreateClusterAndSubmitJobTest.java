package io.kestra.plugin.huawei.mrs;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.huawei.mrs.models.JobConfig;
import io.kestra.plugin.huawei.mrs.models.NodeGroupConfig;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

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
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreateClusterAndSubmitJobTest {

    private static final String PROJECT_ID = "test-project-abc123";
    private static final String FAKE_AK = "FAKEACCESSKEY0001";
    private static final String FAKE_SK = "fakeSecretKey0001fakeSecretKey001";
    private static final String CLUSTER_ID = "cluster-001";

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

    private CreateClusterAndSubmitJob.CreateClusterAndSubmitJobBuilder<?, ?> baseTask() {
        return CreateClusterAndSubmitJob.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .clusterName(Property.ofValue("kestra-test-cluster"))
            .clusterVersion(Property.ofValue("MRS 3.2.0-LTS.3"))
            .clusterType(Property.ofValue(ClusterType.ANALYSIS))
            .components(Property.ofValue(List.of("Hadoop", "Spark2x")))
            .availabilityZone(Property.ofValue("eu-west-101a"))
            .vpcName(Property.ofValue("vpc-default"))
            .subnetName(Property.ofValue("subnet-default"))
            .nodeGroups(Property.ofValue(List.of(
                NodeGroupConfig.builder()
                    .groupName(Property.ofValue("master_node_default_group"))
                    .nodeNum(Property.ofValue(1))
                    .nodeSize(Property.ofValue("c6.2xlarge.4"))
                    .build()
            )))
            .loginMode(Property.ofValue(LoginMode.PASSWORD))
            .nodeRootPassword(Property.ofValue("nodeRootPass123!"))
            .managerAdminPassword(Property.ofValue("managerAdminPass123!"))
            .interval(Property.ofValue(Duration.ofMillis(20)));
    }

    private void stubRunJobFlow() {
        wireMock.stubFor(post(urlMatching(".*/run-job-flow"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"cluster_id\": \"" + CLUSTER_ID + "\"}")));
    }

    private void stubCreateCluster() {
        wireMock.stubFor(post(urlMatching(".*/clusters"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"cluster_id\": \"" + CLUSTER_ID + "\"}")));
    }

    private void stubClusterState(String state) {
        wireMock.stubFor(get(urlMatching(".*/cluster_infos/" + CLUSTER_ID))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"cluster": {"clusterId": "%s", "clusterName": "kestra-test-cluster", "clusterState": "%s"}}
                    """.formatted(CLUSTER_ID, state))));
    }

    private void stubJobList(String jobName, String jobId) {
        wireMock.stubFor(get(urlMatching(".*/clusters/" + CLUSTER_ID + "/job-executions.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"total_record": 1, "job_list": [{"job_id": "%s", "job_name": "%s", "job_state": "RUNNING"}]}
                    """.formatted(jobId, jobName))));
    }

    // ── Happy path ───────────────────────────────────────────────────────────────

    @Test
    void createCluster_waitTrue_withSteps_resolvesClusterAndJobIds() throws Exception {
        stubRunJobFlow();
        stubClusterState("running");
        stubJobList("daily-aggregation", "job-001");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .steps(Property.ofValue(List.of(
                JobConfig.builder()
                    .jobType(Property.ofValue(JobType.SPARK_SUBMIT))
                    .jobName(Property.ofValue("daily-aggregation"))
                    .arguments(Property.ofValue(List.of("--class", "com.example.Main")))
                    .build()
            )))
            .build();

        var output = task.run(runContext);

        assertThat(output.getClusterId(), equalTo(CLUSTER_ID));
        assertThat(output.getClusterState(), equalTo("running"));
        assertThat(output.getJobIds(), hasItem("job-001"));
    }

    @Test
    void createCluster_noSteps_callsCreateClusterNotRunJobFlow() throws Exception {
        stubCreateCluster();
        stubClusterState("running");
        // No run-job-flow or job-list stubs registered — a call to either would fail the test.

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().build();

        var output = task.run(runContext);

        assertThat(output.getClusterId(), equalTo(CLUSTER_ID));
        assertThat(output.getClusterState(), equalTo("running"));
        assertThat(output.getJobIds(), equalTo(List.of()));

        wireMock.verify(WireMock.postRequestedFor(urlMatching(".*/clusters")));
        wireMock.verify(0, WireMock.postRequestedFor(urlMatching(".*/run-job-flow")));
    }

    @Test
    void createCluster_waitFalse_returnsImmediatelyWithoutPolling() throws Exception {
        stubCreateCluster();
        // No cluster-state or job-list stubs registered — a call to either would fail the test.

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().wait(Property.ofValue(false)).build();

        var output = task.run(runContext);

        assertThat(output.getClusterId(), equalTo(CLUSTER_ID));
        assertThat(output.getClusterState(), equalTo(null));
        assertThat(output.getJobIds(), equalTo(List.of()));
    }

    // ── Failure / timeout ────────────────────────────────────────────────────────

    @Test
    void createCluster_clusterFails_throwsActionableError() {
        stubCreateCluster();
        stubClusterState("failed");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("failed"));
        assertThat(ex.getMessage(), containsString(CLUSTER_ID));
    }

    @Test
    void createCluster_clusterAbnormal_throwsActionableError() {
        // `abnormal` (cluster came up unhealthy) never progresses to `running` on its own — must be
        // treated as a terminal failure, not polled forever.
        stubCreateCluster();
        stubClusterState("abnormal");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("abnormal"));
        assertThat(ex.getMessage(), containsString(CLUSTER_ID));
    }

    @Test
    void createCluster_clusterFrozen_throwsActionableError() {
        // `frozen` (e.g. billing arrears) never progresses to `running` on its own — must be treated
        // as a terminal failure, not polled forever.
        stubCreateCluster();
        stubClusterState("frozen");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("frozen"));
        assertThat(ex.getMessage(), containsString(CLUSTER_ID));
    }

    @Test
    void createCluster_startingThenRunning_transientStateDoesNotBlockSuccess() throws Exception {
        // `starting` is transient and must be polled through, not treated as terminal.
        stubCreateCluster();
        wireMock.stubFor(get(urlMatching(".*/cluster_infos/" + CLUSTER_ID))
            .inScenario("cluster-starting")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"cluster": {"clusterId": "%s", "clusterName": "kestra-test-cluster", "clusterState": "starting"}}
                    """.formatted(CLUSTER_ID)))
            .willSetStateTo("running"));
        wireMock.stubFor(get(urlMatching(".*/cluster_infos/" + CLUSTER_ID))
            .inScenario("cluster-starting")
            .whenScenarioStateIs("running")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"cluster": {"clusterId": "%s", "clusterName": "kestra-test-cluster", "clusterState": "running"}}
                    """.formatted(CLUSTER_ID))));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().build();

        var output = task.run(runContext);

        assertThat(output.getClusterId(), equalTo(CLUSTER_ID));
        assertThat(output.getClusterState(), equalTo("running"));
    }

    @Test
    void createCluster_timeout_throwsActionableError() {
        stubCreateCluster();
        stubClusterState("bootstrapping");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .maxDuration(Property.ofValue(Duration.ofMillis(150)))
            .interval(Property.ofValue(Duration.ofMillis(20)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString(CLUSTER_ID));
        assertThat(ex.getMessage(), containsString("running"));
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    @Test
    void createCluster_missingSubnet_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().subnetName(null).subnetId(null).build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("subnetId"));
    }

    @Test
    void createCluster_passwordLoginWithoutNodeRootPassword_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().nodeRootPassword(null).build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("nodeRootPassword"));
    }

    @Test
    void createCluster_keypairLoginWithoutKeypairName_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().loginMode(Property.ofValue(LoginMode.PUBLICKEY)).nodeRootPassword(null).build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("nodeKeypairName"));
    }

    @Test
    void createCluster_nodeNumBelowMinimum_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .nodeGroups(Property.ofValue(List.of(
                NodeGroupConfig.builder()
                    .groupName(Property.ofValue("master_node_default_group"))
                    .nodeNum(Property.ofValue(0))
                    .nodeSize(Property.ofValue("c6.2xlarge.4"))
                    .build()
            )))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("nodeNum"));
        assertThat(ex.getMessage(), containsString("0"));
    }

    @Test
    void createCluster_dataVolumeCountAboveMaximum_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .nodeGroups(Property.ofValue(List.of(
                NodeGroupConfig.builder()
                    .groupName(Property.ofValue("master_node_default_group"))
                    .nodeNum(Property.ofValue(1))
                    .nodeSize(Property.ofValue("c6.2xlarge.4"))
                    .dataVolumeCount(Property.ofValue(21))
                    .build()
            )))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("dataVolumeCount"));
        assertThat(ex.getMessage(), containsString("21"));
    }

    @Test
    void createCluster_customEndpointWithoutProjectId_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().projectId(null).build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("projectId"));
    }

    // ── kill() ───────────────────────────────────────────────────────────────────

    @Test
    void kill_isNoOp() {
        // Clusters are long-lived infra resources, not owned/cancellable by this task —
        // kill() is intentionally not overridden.
        var task = CreateClusterAndSubmitJob.builder().build();
        assertDoesNotThrow(task::kill);
    }
}
