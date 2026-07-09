package io.kestra.plugin.huawei.dli;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.huawei.obs.AuthType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryTest {

    private static final String PROJECT_ID = "test-project-abc123";
    private static final String FAKE_AK = "FAKEACCESSKEY0001";
    private static final String FAKE_SK = "fakeSecretKey0001fakeSecretKey001";
    private static final String JOB_ID = "job-001";

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

    private Query.QueryBuilder<?, ?> baseTask() {
        return Query.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .sql(Property.ofValue("SELECT * FROM my_table"))
            .database(Property.ofValue("my_database"))
            .queue(Property.ofValue("my_queue"))
            .interval(Property.ofValue(Duration.ofMillis(20)));
    }

    private void stubSubmit(String jobType, String jobMode) {
        wireMock.stubFor(post(urlMatching(".*/jobs/submit-job"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"is_success": true, "job_id": "%s", "job_type": "%s", "job_mode": "%s"}
                    """.formatted(JOB_ID, jobType, jobMode))));
    }

    private void stubStatus(String jobType, String status) {
        wireMock.stubFor(get(urlMatching(".*/jobs/" + JOB_ID + "/status"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"job_id": "%s", "job_type": "%s", "status": "%s", "duration": 1234, "is_success": true}
                    """.formatted(JOB_ID, jobType, status))));
    }

    private void stubPreview() {
        wireMock.stubFor(get(urlMatching(".*/jobs/" + JOB_ID + "/preview.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "is_success": true,
                      "job_id": "%s",
                      "job_type": "QUERY",
                      "row_count": 2,
                      "schema": [{"name": "id", "type": "int"}, {"name": "name", "type": "string"}],
                      "rows": [[1, "a"], [2, "b"]]
                    }
                    """.formatted(JOB_ID))));
    }

    // ── FETCH ────────────────────────────────────────────────────────────────────

    @Test
    void query_fetch_happyPath_returnsMappedRows() throws Exception {
        stubSubmit("QUERY", "async");
        stubStatus("QUERY", "FINISHED");
        stubPreview();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().fetchType(Property.ofValue(FetchType.FETCH)).build();

        var output = task.run(runContext);

        assertThat(output.getJobId(), equalTo(JOB_ID));
        assertThat(output.getJobType(), equalTo("QUERY"));
        assertThat(output.getStatus(), equalTo("FINISHED"));
        assertThat(output.getRows().size(), equalTo(2));
        assertThat(output.getRows().get(0).get("id"), equalTo(1));
        assertThat(output.getRows().get(0).get("name"), equalTo("a"));
        assertThat(output.getSize(), equalTo(2L));
    }

    // ── FETCH_ONE ────────────────────────────────────────────────────────────────

    @Test
    void query_fetchOne_returnsFirstRow() throws Exception {
        stubSubmit("QUERY", "async");
        stubStatus("QUERY", "FINISHED");
        stubPreview();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().fetchType(Property.ofValue(FetchType.FETCH_ONE)).build();

        var output = task.run(runContext);

        assertThat(output.getRow().get("id"), equalTo(1));
        assertThat(output.getRow().get("name"), equalTo("a"));
        assertThat(output.getRows(), is(nullValue()));
        assertThat(output.getSize(), equalTo(1L));
    }

    // ── STORE ────────────────────────────────────────────────────────────────────

    @Test
    void query_store_happyPath_downloadsExportedRowsAsIon() throws Exception {
        var exportJobId = "export-job-001";
        var bucket = "dli-test-bucket";
        // Query.store() scopes the export under the query jobId, so concurrent/repeated runs
        // against the same outputLocation never mix or clobber each other's result data.
        var scopedPrefix = "dli-results/" + JOB_ID + "/";
        var objectKey = scopedPrefix + "part-00000.json";

        stubSubmit("QUERY", "async");
        stubStatus("QUERY", "FINISHED");

        wireMock.stubFor(post(urlMatching(".*/jobs/" + JOB_ID + "/export-result"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"is_success": true, "job_id": "%s", "job_mode": "async"}
                    """.formatted(exportJobId))));

        wireMock.stubFor(get(urlMatching(".*/jobs/" + exportJobId + "/status"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"job_id": "%s", "job_type": "EXPORT", "status": "FINISHED", "is_success": true}
                    """.formatted(exportJobId))));

        // OBS list-objects (path-style): GET /{bucket}?prefix=... — urlPathEqualTo ignores the query string.
        wireMock.stubFor(get(urlPathEqualTo("/" + bucket))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/xml")
                .withBody("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ListBucketResult>
                      <Name>%s</Name>
                      <Prefix>%s</Prefix>
                      <Marker></Marker>
                      <MaxKeys>1000</MaxKeys>
                      <IsTruncated>false</IsTruncated>
                      <Contents>
                        <Key>%s</Key>
                        <LastModified>2024-01-01T00:00:00.000Z</LastModified>
                        <ETag>"d41d8cd98f00b204e9800998ecf8427e"</ETag>
                        <Size>42</Size>
                        <StorageClass>STANDARD</StorageClass>
                      </Contents>
                    </ListBucketResult>
                    """.formatted(bucket, scopedPrefix, objectKey))));

        // OBS get-object: the exported ND-JSON part file. The SDK URL-encodes the object key's
        // internal slashes on the request line, so match loosely rather than on the literal path.
        wireMock.stubFor(get(urlPathMatching("/" + bucket + "/.*part-00000\\.json"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"id": 1, "name": "a"}
                    {"id": 2, "name": "b"}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .fetchType(Property.ofValue(FetchType.STORE))
            .outputLocation(Property.ofValue("obs://" + bucket + "/dli-results/"))
            .obsEndpointOverride(Property.ofValue(wireMockUrl()))
            .obsPathStyleAccess(Property.ofValue(true))
            .obsAuthType(Property.ofValue(AuthType.V2))
            .build();

        var output = task.run(runContext);

        assertThat(output.getJobId(), equalTo(JOB_ID));
        assertThat(output.getJobType(), equalTo("QUERY"));
        assertThat(output.getStatus(), equalTo("FINISHED"));
        assertThat(output.getUri(), is(notNullValue()));
        assertThat(output.getSize(), equalTo(2L));

        List<Map> rows;
        try (var reader = new InputStreamReader(runContext.storage().getFile(output.getUri()), StandardCharsets.UTF_8)) {
            rows = FileSerde.readAll(reader, Map.class).collectList().block();
        }
        assertThat(rows.size(), equalTo(2));
        assertThat(rows.get(0).get("id"), equalTo(1));
        assertThat(rows.get(0).get("name"), equalTo("a"));
        assertThat(rows.get(1).get("id"), equalTo(2));
    }

    // ── NONE ─────────────────────────────────────────────────────────────────────

    @Test
    void query_none_returnsWithoutFetchingRows() throws Exception {
        stubSubmit("QUERY", "async");
        stubStatus("QUERY", "FINISHED");
        // No preview stub registered — a call to it would fail the test.

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().fetchType(Property.ofValue(FetchType.NONE)).build();

        var output = task.run(runContext);

        assertThat(output.getJobId(), equalTo(JOB_ID));
        assertThat(output.getStatus(), equalTo("FINISHED"));
        assertThat(output.getRows(), is(nullValue()));
        assertThat(output.getRow(), is(nullValue()));
    }

    // ── Non-QUERY job types ──────────────────────────────────────────────────────

    @Test
    void query_ddlJobType_skipsFetchEvenWhenFetchTypeIsFetch() throws Exception {
        stubSubmit("DDL", "async");
        stubStatus("DDL", "FINISHED");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .sql(Property.ofValue("CREATE TABLE my_table (id INT)"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var output = task.run(runContext);

        assertThat(output.getJobType(), equalTo("DDL"));
        assertThat(output.getRows(), is(nullValue()));
    }

    // ── Failure / cancellation ───────────────────────────────────────────────────

    @Test
    void query_jobFailed_throwsActionableError() {
        stubSubmit("QUERY", "async");
        wireMock.stubFor(get(urlMatching(".*/jobs/" + JOB_ID + "/status"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"job_id": "%s", "job_type": "QUERY", "status": "FAILED", "message": "syntax error near 'FORM'"}
                    """.formatted(JOB_ID))));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().fetchType(Property.ofValue(FetchType.FETCH)).build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("FAILED")));
        assertThat(ex.getMessage(), is(containsString("syntax error")));
    }

    @Test
    void query_jobCancelled_throwsActionableError() {
        stubSubmit("QUERY", "async");
        stubStatus("QUERY", "CANCELLED");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().fetchType(Property.ofValue(FetchType.FETCH)).build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("CANCELLED")));
    }

    @Test
    void query_timeout_throwsActionableError() {
        stubSubmit("QUERY", "async");
        stubStatus("QUERY", "RUNNING");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .fetchType(Property.ofValue(FetchType.FETCH))
            .maxDuration(Property.ofValue(Duration.ofMillis(150)))
            .interval(Property.ofValue(Duration.ofMillis(20)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString(JOB_ID)));
        assertThat(ex.getMessage(), is(containsString("terminal")));
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    @Test
    void query_storeWithoutOutputLocation_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().fetchType(Property.ofValue(FetchType.STORE)).build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("outputLocation")));
    }

    @Test
    void query_fetchOnDefaultQueue_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .queue(Property.ofValue("default"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("default")));
        assertThat(ex.getMessage(), is(containsString("STORE")));
    }

    @Test
    void query_fetchAllOnDefaultQueue_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .queue(Property.ofValue("Default"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("default")));
    }

    @Test
    void query_fetchWithQueueOmitted_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = Query.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .sql(Property.ofValue("SELECT * FROM my_table"))
            .database(Property.ofValue("my_database"))
            // queue omitted — DLI resolves this to the account's default queue
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("default")));
    }

    @Test
    void query_customEndpointWithoutProjectId_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = Query.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            // no projectId — a custom endpoint bypasses the SDK's automatic project discovery
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .sql(Property.ofValue("SELECT 1"))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("projectId")));
    }

    @Test
    void query_missingAccessKey_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = Query.builder()
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .sql(Property.ofValue("SELECT 1"))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("AK/SK")));
    }

    @Test
    void query_noEndpointNoRegion_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = Query.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .sql(Property.ofValue("SELECT 1"))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }

    // ── kill() ───────────────────────────────────────────────────────────────────

    @Test
    void kill_beforeSubmit_isNoOp() {
        var task = Query.builder().build();
        assertDoesNotThrow(task::kill);
    }

    @Test
    void kill_afterSubmit_cancelsRemoteJob() throws Exception {
        stubSubmit("QUERY", "async");
        stubStatus("QUERY", "FINISHED");
        wireMock.stubFor(delete(urlMatching(".*/jobs/" + JOB_ID))
            .willReturn(aResponse().withStatus(200).withBody("{\"is_success\": true}")));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().fetchType(Property.ofValue(FetchType.NONE)).build();

        task.run(runContext);
        task.kill();

        wireMock.verify(WireMock.deleteRequestedFor(urlMatching(".*/jobs/" + JOB_ID)));
    }

    // ── Integration test (guarded) ────────────────────────────────────────────────

    @Test
    @EnabledIfEnvironmentVariable(named = "DLI_TESTS", matches = "true")
    void query_realCloud_storeHappyPath() throws Exception {
        var region = System.getenv("DLI_REGION");
        var ak = System.getenv("HUAWEI_ACCESS_KEY");
        var sk = System.getenv("HUAWEI_SECRET_ACCESS_KEY");
        var projectId = System.getenv("HUAWEI_PROJECT_ID");
        var database = System.getenv("DLI_DATABASE");
        var queue = System.getenv("DLI_QUEUE");
        var outputLocation = System.getenv("DLI_OUTPUT_LOCATION");
        var obsEndpoint = System.getenv("DLI_OBS_ENDPOINT_OVERRIDE");

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Query.builder()
            .accessKeyId(Property.ofValue(ak))
            .secretAccessKey(Property.ofValue(sk))
            .region(Property.ofValue(region))
            .projectId(Property.ofValue(projectId))
            .sql(Property.ofValue("SELECT 1 AS one"))
            .database(Property.ofValue(database))
            .queue(Property.ofValue(queue))
            .outputLocation(Property.ofValue(outputLocation))
            .obsEndpointOverride(obsEndpoint != null ? Property.ofValue(obsEndpoint) : null)
            .build();

        var output = task.run(runContext);

        assertThat(output.getUri(), is(notNullValue()));
        assertThat(output.getSize(), is(notNullValue()));
    }
}
