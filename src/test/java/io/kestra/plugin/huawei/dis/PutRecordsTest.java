package io.kestra.plugin.huawei.dis;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PutRecordsTest {

    private static final String PROJECT_ID = "test-project-abc123";
    private static final String FAKE_AK = "FAKEACCESSKEY0001";
    private static final String FAKE_SK = "fakeSecretKey0001fakeSecretKey001";

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

    private PutRecords.PutRecordsBuilder<?, ?> baseTask() {
        return PutRecords.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .streamName(Property.ofValue("my-stream"));
    }

    private void stubSendRecords(String body) {
        wireMock.stubFor(post(urlMatching(".*/records"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    @Test
    void run_rawMapEntries_happyPath() throws Exception {
        stubSendRecords("""
            {"failed_record_count": 0, "records": [
                {"partition_id": "0", "sequence_number": "seq-1"},
                {"partition_id": "0", "sequence_number": "seq-2"}
            ]}
            """);

        var runContext = runContextFactory.of(Collections.emptyMap());

        // Raw Map<String, Object> entries — the shape a real YAML flow produces, not a typed builder.
        var task = baseTask()
            .from(List.of(
                Map.of("data", "user sign-in event", "partitionKey", "user-1"),
                Map.of("data", "user sign-out event", "partitionKey", "user-1")
            ))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRecordCount(), equalTo(2));
        assertThat(output.getFailedRecordCount(), equalTo(0));
        assertThat(output.getUri(), notNullValue());

        var results = readResults(runContext, output.getUri());
        assertThat(results.size(), equalTo(2));
        assertThat(results.get(0).getSequenceNumber(), equalTo("seq-1"));
    }

    @Test
    void run_partitionIdInsteadOfPartitionKey_happyPath() throws Exception {
        stubSendRecords("""
            {"failed_record_count": 0, "records": [{"partition_id": "1", "sequence_number": "seq-1"}]}
            """);

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .from(Map.of("data", "targeted event", "partitionId", "1"))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRecordCount(), equalTo(1));
        assertThat(output.getFailedRecordCount(), equalTo(0));
    }

    @Test
    void run_missingData_throws() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().from(Map.of("partitionKey", "user-1")).build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage().contains("data"), is(true));
    }

    @Test
    void run_missingPartitionKeyAndId_throws() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().from(Map.of("data", "no routing info")).build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage().contains("partitionKey"), is(true));
    }

    @Test
    void run_recordTooLarge_throws() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var oversized = "x".repeat((int) PutRecords.MAX_RECORD_BYTES + 1);
        var task = baseTask().from(Map.of("data", oversized, "partitionKey", "user-1")).build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage().contains("1 MB"), is(true));
    }

    @Test
    void run_partialFailure_failOnUnsuccessfulTrue_throws() throws Exception {
        stubSendRecords("""
            {"failed_record_count": 1, "records": [
                {"partition_id": "0", "sequence_number": "seq-1"},
                {"error_code": "DIS.4302", "error_message": "Too many requests."}
            ]}
            """);

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .from(List.of(
                Map.of("data", "ok", "partitionKey", "user-1"),
                Map.of("data", "throttled", "partitionKey", "user-1")
            ))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage().contains("1 of 2"), is(true));
    }

    @Test
    void run_partialFailure_failOnUnsuccessfulFalse_returnsOutput() throws Exception {
        stubSendRecords("""
            {"failed_record_count": 1, "records": [
                {"partition_id": "0", "sequence_number": "seq-1"},
                {"error_code": "DIS.4302", "error_message": "Too many requests."}
            ]}
            """);

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .from(List.of(
                Map.of("data", "ok", "partitionKey", "user-1"),
                Map.of("data", "throttled", "partitionKey", "user-1")
            ))
            .failOnUnsuccessfulRecords(Property.ofValue(false))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRecordCount(), equalTo(2));
        assertThat(output.getFailedRecordCount(), equalTo(1));
    }

    @Test
    void run_recordCountExceedsBatchLimit_chunksIntoTwoRequests() throws Exception {
        wireMock.stubFor(post(urlMatching(".*/records"))
            .inScenario("chunking")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"failed_record_count": 0, "records": []}
                    """))
            .willSetStateTo("second-batch"));
        wireMock.stubFor(post(urlMatching(".*/records"))
            .inScenario("chunking")
            .whenScenarioStateIs("second-batch")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"failed_record_count": 1, "records": [
                        {"error_code": "DIS.4302", "error_message": "Too many requests."}
                    ]}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var records = new ArrayList<Map<String, Object>>();
        for (var i = 0; i < 501; i++) {
            records.add(Map.of("data", "record-" + i, "partitionKey", "user-1"));
        }
        var task = baseTask()
            .from(records)
            .failOnUnsuccessfulRecords(Property.ofValue(false))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRecordCount(), equalTo(501));
        assertThat(output.getFailedRecordCount(), equalTo(1));
        wireMock.verify(exactly(2), postRequestedFor(urlMatching(".*/records")));
    }

    @Test
    void run_byteSizeExceedsBatchLimit_chunksIntoTwoRequests() throws Exception {
        wireMock.stubFor(post(urlMatching(".*/records"))
            .inScenario("byte-chunking")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"failed_record_count": 0, "records": []}
                    """))
            .willSetStateTo("second-batch"));
        wireMock.stubFor(post(urlMatching(".*/records"))
            .inScenario("byte-chunking")
            .whenScenarioStateIs("second-batch")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"failed_record_count": 0, "records": []}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());
        // Raw 900KB per record, under the 1MB per-record limit; base64 ≈ 1.2MB each, so only 4 fit in a
        // 5MB batch and the 5th overflows into a second request.
        var oversizedValue = "x".repeat(900 * 1024);
        var records = new ArrayList<Map<String, Object>>();
        for (var i = 0; i < 6; i++) {
            records.add(Map.of("data", oversizedValue, "partitionKey", "user-1"));
        }
        var task = baseTask()
            .from(records)
            .failOnUnsuccessfulRecords(Property.ofValue(false))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRecordCount(), equalTo(6));
        wireMock.verify(exactly(2), postRequestedFor(urlMatching(".*/records")));
    }

    private List<PutRecords.Result> readResults(RunContext runContext, URI uri) throws Exception {
        try (var reader = new InputStreamReader(runContext.storage().getFile(uri), StandardCharsets.UTF_8)) {
            return FileSerde.readAll(reader, PutRecords.Result.class).collectList().block();
        }
    }

    // Integration test (guarded)

    @Test
    @EnabledIfEnvironmentVariable(named = "DIS_TESTS", matches = "true")
    void run_realCloud_happyPath() throws Exception {
        var streamName = System.getenv("DIS_STREAM_NAME");
        var region = System.getenv("DIS_REGION");
        var ak = System.getenv("HUAWEI_ACCESS_KEY");
        var sk = System.getenv("HUAWEI_SECRET_ACCESS_KEY");

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = PutRecords.builder()
            .accessKeyId(Property.ofValue(ak))
            .secretAccessKey(Property.ofValue(sk))
            .region(Property.ofValue(region))
            .streamName(Property.ofValue(streamName))
            .from(Map.of("data", "Kestra DIS integration test", "partitionKey", "kestra-test"))
            .build();

        var output = task.run(runContext);

        assertThat(output.getRecordCount(), equalTo(1));
        assertThat(output.getFailedRecordCount(), equalTo(0));
    }
}
