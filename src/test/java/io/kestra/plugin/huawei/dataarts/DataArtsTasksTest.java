package io.kestra.plugin.huawei.dataarts;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataArtsTasksTest {

    private static final String PROJECT_ID = "test-project-123";
    private static final String JOB_NAME = "my_etl_job";
    private static final long OLD_INSTANCE_ID = 111111111L;
    private static final long INSTANCE_ID = 987654321L;
    private static final String FAKE_AK = "FAKEACCESSKEY0001";
    private static final String FAKE_SK = "fakeSecretKey0001fakeSecretKey001";
    private static final String WORKSPACE_ID = "ws-abc-001";

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
        setupStubs();
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

    // ── Route builders ──────────────────────────────────────────────────────────
    // Kept in one place so a path change fails every stub at once rather than
    // silently drifting from DataArtsService. These MUST mirror the real published
    // routes (/v2/{project_id}/factory/...) — a stub that mirrors whatever the code
    // happens to send validates nothing, which is how the dead /v1/ paths stayed
    // green while every live call 404'd with APIGW.0101.

    private static String startPath(String jobName) {
        // StartJobRun triggers an on-demand run via "Executing a Job Immediately" (run-immediate),
        // NOT "Starting a Job" (/start, which toggles a schedule and returns DLF.3051 on run-once jobs).
        return "/v2/" + PROJECT_ID + "/factory/jobs/" + jobName + "/run-immediate";
    }

    private static String instancesDetailPath(String jobName) {
        return "/v2/" + PROJECT_ID + "/factory/jobs/" + jobName + "/instances/detail";
    }

    private static String instancePath(String jobName, long instanceId) {
        return "/v2/" + PROJECT_ID + "/factory/jobs/" + jobName + "/instances/" + instanceId;
    }

    /**
     * Stop is still on the unpublished /v1/ route — see the {@code stopInstance} javadoc in
     * {@link DataArtsService}. This helper mirrors that so StopJobRun tests keep passing, but it
     * asserts nothing about the real API: no working stop route has been found yet.
     */
    private static String stopPath(String jobName, long instanceId) {
        return "/v1/" + PROJECT_ID + "/jobs/" + jobName + "/instances/" + instanceId + "/stop";
    }

    /**
     * Default stubs use a WireMock scenario so that:
     * - pre-start listInstances (state=STARTED) → returns OLD_INSTANCE_ID (waterMark snapshot)
     * - post-start listInstances (state=POST_START) → returns INSTANCE_ID (the new run)
     * All other stubs are stateless.
     */
    private void setupStubs() {
        // Pre-start list (first call in StartJobRun.run before startJob).
        // v2 puts the job name in the path, so no jobName query-param matcher is needed —
        // the path itself disambiguates.
        wireMock.stubFor(get(urlPathEqualTo(instancesDetailPath(JOB_NAME)))
            .inScenario("start-job")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(instanceListBody(OLD_INSTANCE_ID, "success")))
            .willSetStateTo("post-start"));

        // Start job POST — advances the scenario state
        wireMock.stubFor(post(urlPathEqualTo(startPath(JOB_NAME)))
            .willReturn(aResponse().withStatus(204)));

        // Post-start list (resolveNewestInstance after startJob)
        wireMock.stubFor(get(urlPathEqualTo(instancesDetailPath(JOB_NAME)))
            .inScenario("start-job")
            .whenScenarioStateIs("post-start")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(instanceListBody(INSTANCE_ID, "success"))));

        // Get instance detail
        wireMock.stubFor(get(urlPathEqualTo(instancePath(JOB_NAME, INSTANCE_ID)))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(instanceDetailBody(INSTANCE_ID, "success"))));

        // Stop instance
        wireMock.stubFor(post(urlPathEqualTo(stopPath(JOB_NAME, INSTANCE_ID)))
            .willReturn(aResponse().withStatus(204)));
    }

    private String instanceListBody(long id, String status) {
        return """
            {
              "instances": [
                {
                  "instanceId": %d,
                  "status": "%s",
                  "planTime": 1700000000000,
                  "startTime": 1700000001000,
                  "endTime": 1700000060000,
                  "lastUpdateTime": 1700000060000,
                  "errorMessage": null
                }
              ]
            }
            """.formatted(id, status);
    }

    private String instanceDetailBody(long id, String status) {
        return """
            {
              "instanceId": %d,
              "status": "%s",
              "planTime": 1700000000000,
              "startTime": 1700000001000,
              "endTime": 1700000060000,
              "lastUpdateTime": 1700000060000,
              "errorMessage": null
            }
            """.formatted(id, status);
    }

    // ── StartJobRun ─────────────────────────────────────────────────────────────

    @Test
    void startJobRun_waitTrue_returnsSuccessfulOutput() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .wait(Property.ofValue(true))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(30)))
            .build();

        var output = task.run(runContext);

        assertThat(output.getJobName(), equalTo(JOB_NAME));
        assertThat(output.getInstanceId(), equalTo(INSTANCE_ID));
        assertThat(output.getStatus(), equalTo("success"));
        assertThat(output.getPlanTime(), equalTo(1700000000000L));
        assertThat(output.getStartTime(), equalTo(1700000001000L));
        assertThat(output.getEndTime(), equalTo(1700000060000L));
        assertThat(output.getErrorMessage(), org.hamcrest.Matchers.nullValue());

        // Guard against Content-Type duplication: a doubled header arrives as
        // "application/json, application/json" (comma-joined). The not(containing(","))
        // matcher fails if the signer loop appends a second content-type value.
        wireMock.verify(postRequestedFor(urlPathEqualTo(startPath(JOB_NAME)))
            .withHeader("Content-Type", WireMock.not(WireMock.containing(","))));
    }

    @Test
    void startJobRun_waitFalse_returnsImmediately() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .wait(Property.ofValue(false))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        var output = task.run(runContext);

        assertThat(output.getJobName(), equalTo(JOB_NAME));
        assertThat(output.getInstanceId(), equalTo(INSTANCE_ID));
        // status is whatever the first instance query returned — no further polling
        assertThat(output.getStatus(), notNullValue());
    }

    @Test
    void startJobRun_withJobParams_succeeds() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .jobParams(Property.ofValue(Map.of("env", "test", "date", "2024-01-01")))
            .wait(Property.ofValue(false))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        var output = task.run(runContext);
        assertThat(output.getInstanceId(), equalTo(INSTANCE_ID));
    }

    @Test
    void startJobRun_triggersRunImmediate_notStartSchedule() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .wait(Property.ofValue(false))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        task.run(runContext);

        // Must hit "Executing a Job Immediately" (run-immediate), which produces a single waitable
        // instance — NOT "Starting a Job" (/start), which toggles a schedule and returns DLF.3051 on
        // a run-once job. startPath() resolves to the run-immediate route.
        wireMock.verify(postRequestedFor(urlPathEqualTo(startPath(JOB_NAME))));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(
            "/v2/" + PROJECT_ID + "/factory/jobs/" + JOB_NAME + "/start")));
    }

    @Test
    void startJobRun_listInstances_scopesByJobNameInPath() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .wait(Property.ofValue(false))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        task.run(runContext);

        // The v2 route carries the job name as a path segment, which is what guarantees a
        // substring-named job's instances can never be returned by mistake. The v1 route needed
        // `jobName` + `preciseQuery=true` query params for the same guarantee; asserting on those
        // now would be asserting on a dead API, so this pins the path instead.
        wireMock.verify(getRequestedFor(urlPathEqualTo(instancesDetailPath(JOB_NAME))));
    }

    @Test
    void terminalAndSuccessStates_matchDataArtsStatusEnum() {
        // Finished states per the DataArts Factory instance status enum.
        for (var s : new String[]{"success", "forceSuccess", "ignoreSuccess", "skip-by-depend",
                                  "fail", "running-exception", "manual-stop"}) {
            assertThat("'" + s + "' should be terminal", DataArtsService.isTerminalState(s), is(true));
        }
        // Transient states must not be treated as terminal (else the wait loop would exit early).
        for (var s : new String[]{"waiting", "running", "waiting-confirm", "freeze", "pause"}) {
            assertThat("'" + s + "' should not be terminal", DataArtsService.isTerminalState(s), is(false));
        }
        // null status (freshly-queued instance, status not yet populated) is not terminal.
        assertThat(DataArtsService.isTerminalState(null), is(false));

        // Successful outcomes include operator-forced and ignored-failure successes.
        for (var s : new String[]{"success", "forceSuccess", "ignoreSuccess"}) {
            assertThat("'" + s + "' should be success", DataArtsService.isSuccessState(s), is(true));
        }
        for (var s : new String[]{"fail", "running-exception", "manual-stop", "skip-by-depend"}) {
            assertThat("'" + s + "' should not be success", DataArtsService.isSuccessState(s), is(false));
        }
    }

    @Test
    void startJobRun_withWorkspaceId_passes() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .workspaceId(Property.ofValue(WORKSPACE_ID))
            .jobName(Property.ofValue(JOB_NAME))
            .wait(Property.ofValue(false))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        var output = task.run(runContext);
        assertThat(output.getInstanceId(), equalTo(INSTANCE_ID));
    }

    @Test
    void startJobRun_missingProjectId_throws() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("projectId"));
    }

    @Test
    void startJobRun_maxDurationExceeded_throws() {
        // Pre-start: no prior runs → waterMark = 0
        wireMock.stubFor(get(urlPathEqualTo(instancesDetailPath("slow_job")))
            .inScenario("slow-job")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"instances\":[]}"))
            .willSetStateTo("post-start"));

        wireMock.stubFor(post(urlPathEqualTo(startPath("slow_job")))
            .willReturn(aResponse().withStatus(204)));

        // Post-start: new instance with id > 0, stays in "running" so timeout triggers.
        wireMock.stubFor(get(urlPathEqualTo(instancesDetailPath("slow_job")))
            .inScenario("slow-job")
            .whenScenarioStateIs("post-start")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "instances": [
                        { "instanceId": 111, "status": "running",
                          "planTime": 1700000000000, "startTime": 1700000001000 }
                      ]
                    }
                    """)));

        wireMock.stubFor(get(urlPathEqualTo(instancePath("slow_job", 111)))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    { "instanceId": 111, "status": "running",
                      "planTime": 1700000000000, "startTime": 1700000001000 }
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue("slow_job"))
            .wait(Property.ofValue(true))
            .maxDuration(Property.ofValue(Duration.ofMillis(200)))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("slow_job"));
        assertThat(ex.getMessage(), containsString("terminal"));
    }

    @Test
    void startJobRun_failStatus_throws() {
        // Pre-start: no prior runs → waterMark = 0
        wireMock.stubFor(get(urlPathEqualTo(instancesDetailPath("failing_job")))
            .inScenario("failing-job")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"instances\":[]}"))
            .willSetStateTo("post-start"));

        wireMock.stubFor(post(urlPathEqualTo(startPath("failing_job")))
            .willReturn(aResponse().withStatus(204)));

        // Post-start: new failing instance (instanceId=222 > waterMark=0)
        wireMock.stubFor(get(urlPathEqualTo(instancesDetailPath("failing_job")))
            .inScenario("failing-job")
            .whenScenarioStateIs("post-start")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "instances": [
                        { "instanceId": 222, "status": "fail",
                          "planTime": 1700000000000, "startTime": 1700000001000,
                          "errorMessage": "OOM error in step 2" }
                      ]
                    }
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue("failing_job"))
            .wait(Property.ofValue(true))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("failing_job"));
        assertThat(ex.getMessage(), containsString("fail"));
        assertThat(ex.getMessage(), containsString("OOM error in step 2"));
    }

    /**
     * Verifies that resolveNewestInstance skips a prior run that has a lower instanceId,
     * picking only the newly created one with a higher instanceId.
     */
    @Test
    void startJobRun_staleInstanceSkipped_resolvesNewInstance() throws Exception {
        long staleId = 500L;
        long newId = 600L;
        String staleJob = "anchored_job";

        // Pre-start list returns only the stale instance.
        wireMock.stubFor(get(urlPathEqualTo(instancesDetailPath(staleJob)))
            .inScenario("anchored-start")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(instanceListBody(staleId, "success")))
            .willSetStateTo("new-instance-visible"));

        wireMock.stubFor(post(urlPathEqualTo(startPath(staleJob)))
            .willReturn(aResponse().withStatus(204)));

        // Post-start list returns both the stale and the new instance; new one has higher ID.
        wireMock.stubFor(get(urlPathEqualTo(instancesDetailPath(staleJob)))
            .inScenario("anchored-start")
            .whenScenarioStateIs("new-instance-visible")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "instances": [
                        { "instanceId": %d, "status": "running",
                          "planTime": 1700000100000, "startTime": 1700000101000 },
                        { "instanceId": %d, "status": "success",
                          "planTime": 1700000000000, "startTime": 1700000001000 }
                      ]
                    }
                    """.formatted(newId, staleId))));

        wireMock.stubFor(get(urlPathEqualTo(instancePath(staleJob, newId)))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(instanceDetailBody(newId, "success"))));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(staleJob))
            .wait(Property.ofValue(true))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        var output = task.run(runContext);

        // Must resolve the new instance, not the stale prior run.
        assertThat(output.getInstanceId(), equalTo(newId));
    }

    // ── GetJobRun ────────────────────────────────────────────────────────────────

    @Test
    void getJobRun_withInstanceId_returnsRun() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .instanceId(Property.ofValue(INSTANCE_ID))
            .build();

        var output = task.run(runContext);

        assertThat(output.getJobName(), equalTo(JOB_NAME));
        assertThat(output.getInstanceId(), equalTo(INSTANCE_ID));
        assertThat(output.getStatus(), equalTo("success"));
        assertThat(output.getPlanTime(), is(1700000000000L));
    }

    @Test
    void getJobRun_withSecurityToken_sendsAndSignsTokenHeader() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var token = "STS-TEMP-TOKEN-abc123";

        var task = GetJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .securityToken(Property.ofValue(token))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .instanceId(Property.ofValue(INSTANCE_ID))
            .build();

        task.run(runContext);

        // The token must reach the wire — AKSKSigner's returned header map contains only
        // Authorization/Host/X-Sdk-Date, so it has to be added to the outgoing request explicitly.
        // It must ALSO appear in SignedHeaders, or the server's canonical request won't match ours.
        wireMock.verify(getRequestedFor(urlPathEqualTo(instancePath(JOB_NAME, INSTANCE_ID)))
            .withHeader("X-Security-Token", WireMock.equalTo(token))
            .withHeader("Authorization", WireMock.containing("x-security-token")));
    }

    @Test
    void getJobRun_withoutSecurityToken_omitsTokenHeader() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .instanceId(Property.ofValue(INSTANCE_ID))
            .build();

        task.run(runContext);

        wireMock.verify(getRequestedFor(urlPathEqualTo(instancePath(JOB_NAME, INSTANCE_ID)))
            .withoutHeader("X-Security-Token"));
    }

    @Test
    void getJobRun_noInstanceId_resolvesLatest() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        // GetJobRun uses listInstances (full paging), not the scenario-bound stub.
        // Use a separate job name to avoid scenario state interference.
        var getJobName = "get_latest_job";
        wireMock.stubFor(get(urlPathEqualTo(instancesDetailPath(getJobName)))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(instanceListBody(INSTANCE_ID, "success"))));

        var task = GetJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(getJobName))
            .build();

        var output = task.run(runContext);

        assertThat(output.getJobName(), equalTo(getJobName));
        assertThat(output.getInstanceId(), equalTo(INSTANCE_ID));
        assertThat(output.getStatus(), equalTo("success"));
    }

    @Test
    void getJobRun_noInstances_throws() {
        wireMock.stubFor(get(urlPathEqualTo(instancesDetailPath("empty_job")))
            .withQueryParam("limit", WireMock.equalTo("1"))
            .withQueryParam("offset", WireMock.equalTo("0"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"instances\":[]}")));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue("empty_job"))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("empty_job"));
    }

    // ── StopJobRun ───────────────────────────────────────────────────────────────

    @Test
    void stopJobRun_wait_pollsUntilManualStop() throws Exception {
        // Stub get instance to return manual-stop immediately after the stop call.
        wireMock.stubFor(get(urlPathEqualTo(instancePath(JOB_NAME, INSTANCE_ID)))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(instanceDetailBody(INSTANCE_ID, "manual-stop"))));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StopJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .instanceId(Property.ofValue(INSTANCE_ID))
            .wait(Property.ofValue(true))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        var output = task.run(runContext);

        assertThat(output.getJobName(), equalTo(JOB_NAME));
        assertThat(output.getInstanceId(), equalTo(INSTANCE_ID));
        assertThat(output.getStatus(), equalTo("manual-stop"));
    }

    @Test
    void stopJobRun_waitFalse_returnsImmediately() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StopJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .instanceId(Property.ofValue(INSTANCE_ID))
            .wait(Property.ofValue(false))
            .build();

        var output = task.run(runContext);

        assertThat(output.getJobName(), equalTo(JOB_NAME));
        assertThat(output.getInstanceId(), equalTo(INSTANCE_ID));
        assertThat(output.getStatus(), equalTo("stopping"));
    }

    @Test
    void stopJobRun_maxDurationExceeded_throws() {
        // Instance remains in "running" state indefinitely — stop API accepts but state never transitions.
        wireMock.stubFor(get(urlPathEqualTo(instancePath(JOB_NAME, INSTANCE_ID)))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(instanceDetailBody(INSTANCE_ID, "running"))));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StopJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .instanceId(Property.ofValue(INSTANCE_ID))
            .wait(Property.ofValue(true))
            .maxDuration(Property.ofValue(Duration.ofMillis(200)))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString(JOB_NAME));
        assertThat(ex.getMessage(), containsString("terminal"));
    }

    // ── Signing — AK set but SK missing ─────────────────────────────────────────

    @Test
    void startJobRun_akSetSkMissing_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            // secretAccessKey intentionally not set
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("AK/SK"));
        assertThat(ex.getMessage(), containsString("secretAccessKey"));
    }

    // ── Endpoint resolution ──────────────────────────────────────────────────────

    @Test
    void startJobRun_noEndpointNoRegion_throws() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .jobName(Property.ofValue(JOB_NAME))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("endpointOverride"));
        assertThat(ex.getMessage(), containsString("region"));
    }

    /**
     * Verifies that null status (freshly-queued instance) does not cause an NPE in the polling
     * loop via isTerminalState — the loop must treat null as non-terminal and continue polling.
     */
    @Test
    void startJobRun_nullStatusInstance_treatedAsNonTerminal() throws Exception {
        var nullStatusJob = "null_status_job";

        // Pre-start: no prior runs.
        wireMock.stubFor(get(urlPathEqualTo(instancesDetailPath(nullStatusJob)))
            .inScenario("null-status")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"instances\":[]}"))
            .willSetStateTo("post-start"));

        wireMock.stubFor(post(urlPathEqualTo(startPath(nullStatusJob)))
            .willReturn(aResponse().withStatus(204)));

        // Post-start: instance visible with null status (freshly queued).
        wireMock.stubFor(get(urlPathEqualTo(instancesDetailPath(nullStatusJob)))
            .inScenario("null-status")
            .whenScenarioStateIs("post-start")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"instances":[{"instanceId": 333, "planTime": 1700000000000}]}
                    """)));

        // Instance detail starts with null status, transitions to success.
        wireMock.stubFor(get(urlPathEqualTo(instancePath(nullStatusJob, 333)))
            .inScenario("null-status-detail")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"instanceId\": 333, \"planTime\": 1700000000000}"))
            .willSetStateTo("succeeded"));

        wireMock.stubFor(get(urlPathEqualTo(instancePath(nullStatusJob, 333)))
            .inScenario("null-status-detail")
            .whenScenarioStateIs("succeeded")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(instanceDetailBody(333, "success"))));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(nullStatusJob))
            .wait(Property.ofValue(true))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        var output = task.run(runContext);
        assertThat(output.getInstanceId(), equalTo(333L));
        assertThat(output.getStatus(), equalTo("success"));
    }

    // ── Authentication ───────────────────────────────────────────────────────────

    @Test
    void startJobRun_noCredentials_throws() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("AK/SK"));
    }
}
