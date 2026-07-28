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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataArtsTasksTest {

    private static final String PROJECT_ID = "test-project-123";
    private static final String JOB_NAME = "my_etl_job";
    private static final long INSTANCE_ID = 987654321L;
    private static final String RUN_NAME = "kestra_test_run_0001";
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
    // green while every live call 404'd with APIGW.0101. Every route below is
    // declared in the SDK's DataArtsStudioMeta HttpRequestDef metadata.

    /** Create (POST) and list/status (GET) share this path; the method disambiguates. */
    private static String supplementDataPath() {
        return "/v2/" + PROJECT_ID + "/factory/supplement-data";
    }

    private static String supplementStopPath(String runName) {
        return "/v2/" + PROJECT_ID + "/factory/supplement-data/" + runName + "/stop";
    }

    private static String instancesDetailPath(String jobName) {
        return "/v2/" + PROJECT_ID + "/factory/jobs/" + jobName + "/instances/detail";
    }

    private static String instancePath(String jobName, long instanceId) {
        return "/v2/" + PROJECT_ID + "/factory/jobs/" + jobName + "/instances/" + instanceId;
    }

    /**
     * Default stubs: a supplement-data run named {@link #RUN_NAME} is created and immediately
     * readable with status {@code SUCCESS}, plus job-instance stubs for GetJobRun.
     *
     * <p>The GET stub pins {@code page=0}. The API documents {@code page} as 0-based, so asking for
     * {@code page=1} returns the <em>second</em> page — an empty {@code rows} array for a
     * name-filtered query, indistinguishable from "no such run". That off-by-one made every live
     * {@code StartJobRun} time out waiting for a run it had just created successfully, so requiring
     * the query parameter here is what stops it recurring.
     */
    private void setupStubs() {
        wireMock.stubFor(post(urlPathEqualTo(supplementDataPath()))
            .willReturn(aResponse().withStatus(204)));

        wireMock.stubFor(get(urlPathEqualTo(supplementDataPath()))
            .withQueryParam("name", WireMock.equalTo(RUN_NAME))
            .withQueryParam("page", WireMock.equalTo("0"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(supplementDataListBody(RUN_NAME, "SUCCESS"))));

        wireMock.stubFor(post(urlPathEqualTo(supplementStopPath(RUN_NAME)))
            .willReturn(aResponse().withStatus(204)));

        wireMock.stubFor(get(urlPathEqualTo(instancesDetailPath(JOB_NAME)))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(instanceListBody(INSTANCE_ID, "success"))));

        wireMock.stubFor(get(urlPathEqualTo(instancePath(JOB_NAME, INSTANCE_ID)))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(instanceDetailBody(INSTANCE_ID, "success"))));
    }

    // Field names below are snake_case because that is what the API returns on the wire — verified
    // against the @JsonProperty names on the SDK's own JobInstance / SupplementDataRespRows models.
    // These fixtures previously used camelCase, which matched the (wrong) keys the mapper read, so the
    // tests passed while every real response mapped to nulls. Keep these spellings aligned with the SDK.

    private String supplementDataListBody(String name, String status) {
        return """
            {
              "total": 1,
              "success": true,
              "rows": [
                {
                  "name": "%s",
                  "job_list": ["%s"],
                  "status": "%s",
                  "start_date": 1700000000000,
                  "end_date": 1700086400000,
                  "submitted_date": 1700000000500,
                  "parallel": 1,
                  "type": 1,
                  "user_name": "tester"
                }
              ]
            }
            """.formatted(name, JOB_NAME, status);
    }

    private String instanceListBody(long id, String status) {
        return """
            {
              "total": 1,
              "instances": [
                {
                  "instance_id": %d,
                  "job_instance_name": "job_instance_%d",
                  "status": "%s",
                  "plan_time": 1700000000000,
                  "start_time": 1700000001000,
                  "end_time": 1700000060000,
                  "execute_time": 59000,
                  "submit_time": 1700000000500,
                  "job_id": 366647
                }
              ]
            }
            """.formatted(id, id, status);
    }

    private String instanceDetailBody(long id, String status) {
        return """
            {
              "instance_id": %d,
              "job_instance_name": "job_instance_%d",
              "status": "%s",
              "plan_time": 1700000000000,
              "start_time": 1700000001000,
              "end_time": 1700000060000,
              "execute_time": 59000,
              "submit_time": 1700000000500,
              "job_id": 366647
            }
            """.formatted(id, id, status);
    }

    private StartJobRun.StartJobRunBuilder<?, ?> startTask() {
        return StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .runName(Property.ofValue(RUN_NAME))
            .interval(Property.ofValue(Duration.ofMillis(50)));
    }

    // ── StartJobRun ─────────────────────────────────────────────────────────────

    @Test
    void startJobRun_waitTrue_returnsSuccessfulOutput() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var output = startTask()
            .wait(Property.ofValue(true))
            .maxDuration(Property.ofValue(Duration.ofSeconds(30)))
            .build()
            .run(runContext);

        assertThat(output.getJobName(), equalTo(JOB_NAME));
        assertThat(output.getRunName(), equalTo(RUN_NAME));
        assertThat(output.getStatus(), equalTo("SUCCESS"));
        // Explicitly cover every snake_case-mapped field: reading these as camelCase silently
        // produced nulls against the real API while the old camelCase fixtures stayed green.
        assertThat(output.getJobList(), contains(JOB_NAME));
        assertThat(output.getStartDate(), equalTo(Instant.ofEpochMilli(1700000000000L)));
        assertThat(output.getEndDate(), equalTo(Instant.ofEpochMilli(1700086400000L)));
        assertThat(output.getSubmittedDate(), equalTo(Instant.ofEpochMilli(1700000000500L)));
        assertThat(output.getParallel(), equalTo(1));
        assertThat(output.getUserName(), equalTo("tester"));

        // Content-Type must be present exactly once. The gateway declares it required on the factory
        // POST routes and rejects the call with APIGW.0106 without it, while a duplicate arrives
        // comma-joined as "application/json, application/json" and breaks signature verification.
        //
        // The equalTo assertion is what makes this meaningful: a lone not(containing(",")) matcher
        // also passes when the header is absent entirely, which is exactly how a missing Content-Type
        // shipped despite this test.
        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withHeader("Content-Type", WireMock.equalTo("application/json"))
            .withHeader("Content-Type", WireMock.not(WireMock.containing(","))));
    }

    @Test
    void startJobRun_waitFalse_returnsImmediately() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var output = startTask().wait(Property.ofValue(false)).build().run(runContext);

        assertThat(output.getRunName(), equalTo(RUN_NAME));
        assertThat(output.getStatus(), notNullValue());
    }

    /**
     * The request body must use the snake_case keys the API expects. Sending camelCase here is the
     * write-side equivalent of the read-side bug that made every output field null.
     *
     * <p>The dates must reach the wire in the {@code 2026-07-28T00:00:00 +00} form DataArts' API
     * reference documents, and the default range must span a whole day rather than a single instant.
     * Any other spelling is silently unparsed server-side and both ends collapse to the same instant,
     * which surfaces as {@code DLF.30121} ("The end time should be at least 2 second later than the
     * start time") — verified live for {@code yyyy-MM-dd}, {@code yyyy-MM-dd HH:mm:ss} and epoch
     * millis alike, so this assertion is what keeps the task working at all.
     */
    @Test
    void startJobRun_sendsSnakeCaseBody_withFullDayDefaultRange() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var today = LocalDate.now(ZoneOffset.UTC).toString();

        startTask().wait(Property.ofValue(false)).build().run(runContext);

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withRequestBody(WireMock.containing("\"job_name\":\"" + JOB_NAME + "\""))
            .withRequestBody(WireMock.containing("\"name\":\"" + RUN_NAME + "\""))
            .withRequestBody(WireMock.containing("\"start_date\":\"" + today + "T00:00:00 +00\""))
            .withRequestBody(WireMock.containing("\"end_date\":\"" + today + "T23:59:59 +00\""))
            .withRequestBody(WireMock.containing("\"is_day_granularity\":true"))
            .withRequestBody(WireMock.containing("\"is_stop_when_fail\":true"))
            .withRequestBody(WireMock.containing("\"parallel\":1")));
    }

    /**
     * An explicit {@code startDate} with no {@code endDate} must still produce a non-zero range,
     * in every accepted input form.
     */
    @Test
    void startJobRun_startDateOnly_derivesEndOfThatDay() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        startTask()
            .startDate(Property.ofValue("2026-07-01"))
            .wait(Property.ofValue(false))
            .build()
            .run(runContext);

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withRequestBody(WireMock.containing("\"start_date\":\"2026-07-01T00:00:00 +00\""))
            .withRequestBody(WireMock.containing("\"end_date\":\"2026-07-01T23:59:59 +00\"")));
    }

    /** A space-separated date-time is a plausible thing to write, so it must convert too. */
    @Test
    void startJobRun_spaceSeparatedDateTime_isConvertedToTheWireForm() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        startTask()
            .startDate(Property.ofValue("2026-07-01 06:30:00"))
            .endDate(Property.ofValue("2026-07-01 18:45:15"))
            .wait(Property.ofValue(false))
            .build()
            .run(runContext);

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withRequestBody(WireMock.containing("\"start_date\":\"2026-07-01T06:30:00 +00\""))
            .withRequestBody(WireMock.containing("\"end_date\":\"2026-07-01T18:45:15 +00\"")));
    }

    /**
     * A value already in the exact wire form ({@code yyyy-MM-ddTHH:mm:ss ±HH}, no minutes on the
     * offset) is the escape hatch for a spelling this task doesn't otherwise generate, so it must
     * reach the API byte-for-byte — including a non-UTC offset.
     */
    @Test
    void startJobRun_exactWireFormDates_arePassedThroughVerbatim() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        startTask()
            .startDate(Property.ofValue("2026-07-01T00:00:00 +08"))
            .endDate(Property.ofValue("2026-07-01T23:59:59 +08"))
            .wait(Property.ofValue(false))
            .build()
            .run(runContext);

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withRequestBody(WireMock.containing("\"start_date\":\"2026-07-01T00:00:00 +08\""))
            .withRequestBody(WireMock.containing("\"end_date\":\"2026-07-01T23:59:59 +08\"")));
    }

    /**
     * A trailing {@code Z} is the ISO spelling a Kestra expression like
     * {@code {{ now() }}} commonly produces. It must be converted to the wire form (as UTC), not
     * passed through verbatim — the old, over-broad "looks like it has an offset" regex used to
     * forward it untouched, and DataArts silently fails to parse a bare {@code Z} suffix.
     */
    @Test
    void startJobRun_zSuffixDates_areConvertedToUtcWireForm() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        startTask()
            .startDate(Property.ofValue("2026-07-01T00:00:00Z"))
            .endDate(Property.ofValue("2026-07-01T23:59:59Z"))
            .wait(Property.ofValue(false))
            .build()
            .run(runContext);

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withRequestBody(WireMock.containing("\"start_date\":\"2026-07-01T00:00:00 +00\""))
            .withRequestBody(WireMock.containing("\"end_date\":\"2026-07-01T23:59:59 +00\"")));
    }

    /**
     * A {@code +HH:mm} offset is converted to UTC rather than re-emitted with its original offset —
     * the wire form DataArts documents only ever shows a whole-hour offset, so this is also the case
     * that exercises an actual instant shift (2 hours earlier here), not just a re-spelling.
     */
    @Test
    void startJobRun_explicitOffsetDates_areConvertedToUtcWireForm() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        startTask()
            .startDate(Property.ofValue("2026-07-01T00:00:00+02:00"))
            .endDate(Property.ofValue("2026-07-01T23:59:59+02:00"))
            .wait(Property.ofValue(false))
            .build()
            .run(runContext);

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withRequestBody(WireMock.containing("\"start_date\":\"2026-06-30T22:00:00 +00\""))
            .withRequestBody(WireMock.containing("\"end_date\":\"2026-07-01T21:59:59 +00\"")));
    }

    /** Fractional seconds must not break offset parsing, and are silently dropped on conversion. */
    @Test
    void startJobRun_fractionalSecondsWithZSuffix_areConvertedAndTruncated() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        startTask()
            .startDate(Property.ofValue("2026-07-01T00:00:00.123Z"))
            .endDate(Property.ofValue("2026-07-01T23:59:59.999Z"))
            .wait(Property.ofValue(false))
            .build()
            .run(runContext);

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withRequestBody(WireMock.containing("\"start_date\":\"2026-07-01T00:00:00 +00\""))
            .withRequestBody(WireMock.containing("\"end_date\":\"2026-07-01T23:59:59 +00\"")));
    }

    /**
     * A non-whole-hour offset (e.g. {@code +05:30}) has no representation in the {@code ±HH} wire
     * form DataArts documents, so it is converted to UTC rather than rounded or rejected — UTC is
     * always exactly representable in that form.
     */
    @Test
    void startJobRun_nonWholeHourOffsetDates_areConvertedToUtcWireForm() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        startTask()
            .startDate(Property.ofValue("2026-07-01T05:30:00+05:30"))
            .endDate(Property.ofValue("2026-07-02T05:30:00+05:30"))
            .wait(Property.ofValue(false))
            .build()
            .run(runContext);

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withRequestBody(WireMock.containing("\"start_date\":\"2026-07-01T00:00:00 +00\""))
            .withRequestBody(WireMock.containing("\"end_date\":\"2026-07-02T00:00:00 +00\"")));
    }

    /**
     * Epoch millis were tried live and rejected, so they must fail with an explanation rather than
     * being forwarded to produce another opaque {@code DLF.30121}.
     */
    @Test
    void startJobRun_epochMillis_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = startTask()
            .startDate(Property.ofValue("1785196800000"))
            .endDate(Property.ofValue("1785283199000"))
            .wait(Property.ofValue(false))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("startDate"));
        assertThat(ex.getMessage(), containsString("Epoch milliseconds are not accepted"));
    }

    /**
     * A malformed {@code startDate} that used to slip through the old, over-broad "looks like it has
     * an offset" regex (and then fail later, inside {@code sameDayAs}, with a confusing "endDate is
     * required" message) must now fail at the format check itself: the escape hatch only matches a
     * value byte-for-byte in the exact wire form, so a {@code dd/MM/yyyy}-style date can no longer
     * reach it.
     */
    @Test
    void startJobRun_malformedStartDateWithOffsetSuffix_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = startTask()
            .startDate(Property.ofValue("01/07/2026 00:00:00 +00"))
            .wait(Property.ofValue(false))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("startDate ('01/07/2026 00:00:00 +00')"));
        assertThat(ex.getMessage(), containsString("is not a date or date-time"));
    }

    /** A startDate in no recognised form fails on the format check, before endDate is derived. */
    @Test
    void startJobRun_unparseableStartDate_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = startTask()
            .startDate(Property.ofValue("01/07/2026"))
            .wait(Property.ofValue(false))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("startDate ('01/07/2026')"));
        assertThat(ex.getMessage(), containsString("is not a date or date-time"));
    }

    /** A date-only {@code endDate} means through the end of that day, not midnight at its start. */
    @Test
    void startJobRun_explicitDateRangeAndParallel_areSent() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        startTask()
            .startDate(Property.ofValue("2026-07-01"))
            .endDate(Property.ofValue("2026-07-31"))
            .parallel(Property.ofValue(4))
            .stopWhenFail(Property.ofValue(false))
            .dayGranularity(Property.ofValue(false))
            .wait(Property.ofValue(false))
            .build()
            .run(runContext);

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withRequestBody(WireMock.containing("\"start_date\":\"2026-07-01T00:00:00 +00\""))
            .withRequestBody(WireMock.containing("\"end_date\":\"2026-07-31T23:59:59 +00\""))
            .withRequestBody(WireMock.containing("\"parallel\":4"))
            .withRequestBody(WireMock.containing("\"is_stop_when_fail\":false"))
            .withRequestBody(WireMock.containing("\"is_day_granularity\":false")));
    }

    /**
     * Pins the trigger route. Both {@code run-immediate} and {@code start} reject every request with
     * DLF.3051 on standard and sovereign regions alike, and neither is declared in the SDK's route
     * metadata — supplement-data is the only mechanism that actually creates a run.
     */
    @Test
    void startJobRun_usesSupplementData_notRunImmediateOrStart() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        startTask().wait(Property.ofValue(false)).build().run(runContext);

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath())));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(
            "/v2/" + PROJECT_ID + "/factory/jobs/" + JOB_NAME + "/run-immediate")));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(
            "/v2/" + PROJECT_ID + "/factory/jobs/" + JOB_NAME + "/start")));
    }

    /**
     * With no {@code runName}, a unique one is generated — the name is the only handle for polling
     * and for StopJobRun, so a collision with an existing run would break both.
     */
    @Test
    void startJobRun_generatesRunName_whenOmitted() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StartJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .jobName(Property.ofValue(JOB_NAME))
            .maxDuration(Property.ofValue(Duration.ofMillis(150)))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        // The generated name has no matching GET stub, so the run never becomes visible and the
        // task times out — which is precisely what isolates the name-generation assertion below.
        assertThrows(IllegalStateException.class, () -> task.run(runContext));

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withRequestBody(WireMock.containing("\"name\":\"kestra_" + JOB_NAME + "_")));
    }

    @Test
    void startJobRun_runNeverVisible_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        wireMock.stubFor(get(urlPathEqualTo(supplementDataPath()))
            .withQueryParam("name", WireMock.equalTo("ghost_run"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"total\":0,\"rows\":[]}")));

        var task = startTask()
            .runName(Property.ofValue("ghost_run"))
            .maxDuration(Property.ofValue(Duration.ofMillis(150)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("ghost_run"));
        assertThat(ex.getMessage(), containsString("never appeared"));
    }

    /**
     * A row whose name merely resembles the requested one must not be reported as this run's status:
     * the API treats {@code name} as a filter rather than an exact key.
     */
    @Test
    void startJobRun_prefixMatchingRow_isNotMistakenForThisRun() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        wireMock.stubFor(get(urlPathEqualTo(supplementDataPath()))
            .withQueryParam("name", WireMock.equalTo("run_a"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(supplementDataListBody("run_a_other", "SUCCESS"))));

        var task = startTask()
            .runName(Property.ofValue("run_a"))
            .maxDuration(Property.ofValue(Duration.ofMillis(150)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("never appeared"));
    }

    /**
     * {@code supplement_data_run_time} stays out of the body unless asked for: a live run with the
     * field absent executed all of its instances immediately, so the API's documented
     * {@code 00:00-00:00} default does not restrict execution despite reading like a zero-width
     * window. Sending a window by default would risk confining runs for no reason.
     */
    @Test
    void startJobRun_omitsTheRunWindow_byDefault() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        startTask().wait(Property.ofValue(false)).build().run(runContext);

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withRequestBody(WireMock.notContaining("supplement_data_run_time")));
    }

    @Test
    void startJobRun_explicitRunWindow_isSent() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        startTask()
            .runTimeWindow(Property.ofValue("01:00-05:00"))
            .wait(Property.ofValue(false))
            .build()
            .run(runContext);

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withRequestBody(WireMock.containing(
                "\"supplement_data_run_time\":{\"time_of_day\":\"01:00-05:00\"}")));
    }

    /**
     * {@code page} is 0-based on this API ("default 0, must be ≥ 0"), not 1-based like the
     * {@code offset} conventions elsewhere in Huawei's APIs. Sending {@code page=1} asks for the
     * second page, which for a name-filtered query is empty — so a created run reads back as
     * missing and every {@code StartJobRun} times out. Asserted explicitly because the symptom
     * ("never appeared in the run list") points at creation rather than at the query.
     */
    @Test
    void startJobRun_queriesTheFirstPage_zeroBased() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        startTask().wait(Property.ofValue(false)).build().run(runContext);

        wireMock.verify(getRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withQueryParam("name", WireMock.equalTo(RUN_NAME))
            .withQueryParam("page", WireMock.equalTo("0")));
    }

    @Test
    void startJobRun_maxDurationExceeded_throws() {
        wireMock.stubFor(get(urlPathEqualTo(supplementDataPath()))
            .withQueryParam("name", WireMock.equalTo("slow_run"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(supplementDataListBody("slow_run", "RUNNING"))));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = startTask()
            .runName(Property.ofValue("slow_run"))
            .wait(Property.ofValue(true))
            .maxDuration(Property.ofValue(Duration.ofMillis(200)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("slow_run"));
        assertThat(ex.getMessage(), containsString("terminal"));
    }

    @Test
    void startJobRun_failStatus_throws() {
        wireMock.stubFor(get(urlPathEqualTo(supplementDataPath()))
            .withQueryParam("name", WireMock.equalTo("failing_run"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(supplementDataListBody("failing_run", "FAIL"))));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = startTask()
            .runName(Property.ofValue("failing_run"))
            .wait(Property.ofValue(true))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("failing_run"));
        assertThat(ex.getMessage(), containsString("FAIL"));
    }

    @Test
    void startJobRun_dlfRejectsBody_surfacesDateFormatHint() {
        wireMock.stubFor(post(urlPathEqualTo(supplementDataPath()))
            .willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\":\"DLF.3051\",\"error_msg\":\"The request parameter is invalid.\"}")));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var ex = assertThrows(IllegalStateException.class,
            () -> startTask().wait(Property.ofValue(false)).build().run(runContext));

        assertThat(ex.getMessage(), containsString("DLF.3051"));
        // The hint must point at the undocumented date format, the actual likely cause, and name
        // the properties a flow author can act on rather than the snake_case wire keys.
        assertThat(ex.getMessage(), containsString("startDate/endDate"));
        assertThat(ex.getMessage(), containsString("yyyy-MM-ddTHH:mm:ss +00"));
    }

    @Test
    void startJobRun_withWorkspaceId_passes() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var output = startTask()
            .workspaceId(Property.ofValue(WORKSPACE_ID))
            .wait(Property.ofValue(false))
            .build()
            .run(runContext);

        assertThat(output.getRunName(), equalTo(RUN_NAME));
        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementDataPath()))
            .withHeader("workspace", WireMock.equalTo(WORKSPACE_ID)));
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

    /**
     * A {@code runName} containing a space and a non-ASCII character must reach the {@code GET}
     * query string encoded exactly once. The fixed-value fixtures used everywhere else in this class
     * ({@code kestra_test_run_0001}, {@code my_etl_job}) are all alnum/underscore, which
     * {@code URLEncoder} never touches — so this is the only test that would have caught the
     * double-encoding bug: the old code pre-encoded {@code name} into the query string, then
     * {@code invoke()} re-split that already-encoded string and re-added it via
     * {@code addQueryParam}, which the SDK's signer encoded a <em>second</em> time for the canonical
     * request it signs — an opaque {@code APIGW.0301} against a live API, while every WireMock test
     * using an alnum name stayed green throughout.
     */
    @Test
    void startJobRun_runNameWithSpaceAndNonAscii_reachesTheCorrectlyEncodedQueryUrl() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var runName = "kestra café run";

        wireMock.stubFor(get(urlPathEqualTo(supplementDataPath()))
            .withQueryParam("name", WireMock.equalTo(runName))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(supplementDataListBody(runName, "SUCCESS"))));

        var output = startTask()
            .runName(Property.ofValue(runName))
            .wait(Property.ofValue(false))
            .build()
            .run(runContext);

        assertThat(output.getRunName(), equalTo(runName));

        var received = wireMock.findAll(getRequestedFor(urlPathEqualTo(supplementDataPath())));
        assertThat(received, hasSize(1));
        // Pins the actual wire bytes: a double-encode (the original bug) would leave a literal
        // '%25' sequence here instead of a single percent-encoded UTF-8 accented character.
        assertThat(received.get(0).getUrl(), containsString("name=kestra%20caf%C3%A9%20run"));
    }

    /**
     * The analogous case for a {@code runName} used as a <em>path</em> segment (StopJobRun), where
     * {@code URLEncoder}'s '+' for space is doubly wrong: it's not valid in a path segment in the
     * first place (a server would read it as a literal plus sign, not a space), and re-encoding an
     * already-encoded segment during signing would produce yet another, disagreeing value.
     */
    @Test
    void stopJobRun_runNameWithSpaceAndNonAscii_reachesTheCorrectlyEncodedPathUrl() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var runName = "kestra café run";

        wireMock.stubFor(post(urlPathMatching(
                "/v2/" + PROJECT_ID + "/factory/supplement-data/.*/stop"))
            .willReturn(aResponse().withStatus(204)));

        var task = StopJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .runName(Property.ofValue(runName))
            .wait(Property.ofValue(false))
            .build();

        task.run(runContext);

        var received = wireMock.findAll(postRequestedFor(urlPathMatching(
            "/v2/" + PROJECT_ID + "/factory/supplement-data/.*/stop")));
        assertThat(received, hasSize(1));
        assertThat(received.get(0).getUrl(), containsString(
            "/v2/" + PROJECT_ID + "/factory/supplement-data/kestra%20caf%C3%A9%20run/stop"));
    }

    // ── kill() ───────────────────────────────────────────────────────────────────

    @Test
    void startJobRun_kill_stopsTheSupplementDataRun() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = startTask().wait(Property.ofValue(false)).build();
        task.run(runContext);

        task.kill();

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementStopPath(RUN_NAME))));
    }

    /**
     * A kill signal arriving before the run is even created must still be honoured once the run
     * name becomes known: {@code killable} is set right after {@code createSupplementData} succeeds,
     * and {@code isKilled} is checked immediately afterwards to close exactly this race.
     */
    @Test
    void startJobRun_killBeforeRun_stopsAsSoonAsRunNameIsKnown() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = startTask().wait(Property.ofValue(false)).build();
        task.kill();
        task.run(runContext);

        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementStopPath(RUN_NAME))));
    }

    /** kill() must never propagate a failure from the best-effort stop call. */
    @Test
    void startJobRun_kill_neverThrows_whenStopFails() throws Exception {
        wireMock.stubFor(post(urlPathEqualTo(supplementStopPath(RUN_NAME)))
            .willReturn(aResponse().withStatus(500)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = startTask().wait(Property.ofValue(false)).build();
        task.run(runContext);

        assertDoesNotThrow(task::kill);
    }

    // ── Status vocabularies ─────────────────────────────────────────────────────

    /**
     * Job-instance statuses, per the SDK's {@code JobInstance$StatusEnum}: {@code waiting},
     * {@code running}, {@code success}, {@code fail}, {@code manual}, {@code pause}, {@code skip},
     * {@code freeze} — and nothing else.
     *
     * <p>This test previously asserted invented values ({@code forceSuccess}, {@code ignoreSuccess},
     * {@code skip-by-depend}, {@code running-exception}, {@code manual-stop}) as terminal.
     * {@code force_success}/{@code ignore_success} are separate <em>boolean</em> fields on the
     * instance and never appear as a status; the hyphenated spellings are not in the enum at all.
     */
    @Test
    void jobInstanceTerminalAndSuccessStates_matchSdkStatusEnum() {
        for (var s : new String[]{"success", "fail", "skip"}) {
            assertThat("'" + s + "' should be terminal", DataArtsService.isTerminalState(s), is(true));
        }
        // manual/pause/freeze await an operator, so they are deliberately non-terminal.
        for (var s : new String[]{"waiting", "running", "manual", "pause", "freeze"}) {
            assertThat("'" + s + "' should not be terminal", DataArtsService.isTerminalState(s), is(false));
        }
        // Values that are not in the enum must not be treated as terminal.
        for (var s : new String[]{"forceSuccess", "ignoreSuccess", "skip-by-depend",
                                  "running-exception", "manual-stop"}) {
            assertThat("'" + s + "' is not a real status", DataArtsService.isTerminalState(s), is(false));
        }
        // null status (freshly-queued instance, status not yet populated) is not terminal.
        assertThat(DataArtsService.isTerminalState(null), is(false));

        assertThat(DataArtsService.isSuccessState("success"), is(true));
        for (var s : new String[]{"fail", "skip", "forceSuccess", "ignoreSuccess"}) {
            assertThat("'" + s + "' should not be success", DataArtsService.isSuccessState(s), is(false));
        }
    }

    /**
     * Supplement-data statuses are <b>UPPER CASE</b> — {@code SUCCESS} / {@code RUNNING} /
     * {@code CANCEL}, per the {@code status} filter and the response example in the "Querying
     * PatchData Instances" reference. That is a different vocabulary from the lower-case job-instance
     * statuses above, and matching them case-sensitively against the lower-case set meant a finished
     * run was never recognised as terminal — it polled until {@code maxDuration}.
     *
     * <p>No SDK enum exists, and the doc enumerates only the three filterable values, so matching is
     * case-insensitive and covers plausible failure/cancellation spellings. An unrecognised value must
     * stay non-terminal so the worst case is a timeout, never a wrong reported outcome.
     */
    @Test
    void supplementDataTerminalStates_areUpperCase_andTreatUnknownAsNonTerminal() {
        for (var s : new String[]{"SUCCESS", "FAIL", "FAILED", "CANCEL", "CANCELED", "CANCELLED",
                                  "STOP", "STOPPED"}) {
            assertThat("'" + s + "' should be terminal",
                DataArtsService.isSupplementDataTerminalState(s), is(true));
        }
        // Case-insensitive: the doc shows upper case, but nothing guarantees it across regions.
        for (var s : new String[]{"success", "Success", "cancel"}) {
            assertThat("'" + s + "' should be terminal regardless of case",
                DataArtsService.isSupplementDataTerminalState(s), is(true));
        }
        for (var s : new String[]{"RUNNING", "WAITING", "some-unknown-status", ""}) {
            assertThat("'" + s + "' should not be terminal",
                DataArtsService.isSupplementDataTerminalState(s), is(false));
        }
        assertThat(DataArtsService.isSupplementDataTerminalState(null), is(false));

        assertThat(DataArtsService.isSupplementDataSuccessState("SUCCESS"), is(true));
        assertThat(DataArtsService.isSupplementDataSuccessState("success"), is(true));
        for (var s : new String[]{"FAIL", "STOPPED", "CANCEL", "RUNNING", null}) {
            assertThat(DataArtsService.isSupplementDataSuccessState(s), is(false));
        }
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
        assertThat(output.getPlanTime(), is(Instant.ofEpochMilli(1700000000000L)));
        // Fields that silently mapped to null while the mapper read camelCase keys.
        assertThat(output.getJobInstanceName(), equalTo("job_instance_" + INSTANCE_ID));
        // execute_time is an elapsed duration, not an epoch timestamp — 59 s here, matching
        // end_time - start_time in the fixture.
        assertThat(output.getExecuteTime(), equalTo(Duration.ofSeconds(59)));
        assertThat(output.getSubmitTime(), equalTo(Instant.ofEpochMilli(1700000000500L)));
        assertThat(output.getJobId(), equalTo(366647L));
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
    void stopJobRun_wait_pollsUntilStopped() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo(supplementDataPath()))
            .withQueryParam("name", WireMock.equalTo(RUN_NAME))
            .inScenario("stop-run")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(supplementDataListBody(RUN_NAME, "RUNNING")))
            .willSetStateTo("cancelled"));

        wireMock.stubFor(get(urlPathEqualTo(supplementDataPath()))
            .withQueryParam("name", WireMock.equalTo(RUN_NAME))
            .inScenario("stop-run")
            .whenScenarioStateIs("cancelled")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(supplementDataListBody(RUN_NAME, "CANCEL"))));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StopJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .runName(Property.ofValue(RUN_NAME))
            .wait(Property.ofValue(true))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRunName(), equalTo(RUN_NAME));
        assertThat(output.getStatus(), equalTo("CANCEL"));
        wireMock.verify(postRequestedFor(urlPathEqualTo(supplementStopPath(RUN_NAME))));
    }

    @Test
    void stopJobRun_waitFalse_returnsImmediately() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StopJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .runName(Property.ofValue(RUN_NAME))
            .wait(Property.ofValue(false))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRunName(), equalTo(RUN_NAME));
        assertThat(output.getStatus(), equalTo("stopping"));
    }

    @Test
    void stopJobRun_maxDurationExceeded_throws() {
        // Run stays "running" — the stop request is accepted but never confirmed.
        wireMock.stubFor(get(urlPathEqualTo(supplementDataPath()))
            .withQueryParam("name", WireMock.equalTo(RUN_NAME))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(supplementDataListBody(RUN_NAME, "RUNNING"))));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = StopJobRun.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .runName(Property.ofValue(RUN_NAME))
            .wait(Property.ofValue(true))
            .maxDuration(Property.ofValue(Duration.ofMillis(200)))
            .interval(Property.ofValue(Duration.ofMillis(50)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString(RUN_NAME));
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
