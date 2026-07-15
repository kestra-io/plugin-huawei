package io.kestra.plugin.huawei.dis;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.huawei.dis.models.Record;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumeTest {

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

    private Consume.ConsumeBuilder<?, ?> baseTask() {
        return Consume.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .streamName(Property.ofValue("my-stream"))
            .maxRecords(Property.ofValue(100));
    }

    private void stubSinglePartitionStream() {
        wireMock.stubFor(get(urlMatching(".*/streams/.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"stream_name": "my-stream", "partitions": [{"partition_id": "0", "status": "ACTIVE"}], "has_more_partitions": false}
                    """)));
    }

    private void stubCursor() {
        wireMock.stubFor(get(urlMatching(".*/cursors.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"partition_cursor": "initial-cursor"}
                    """)));
    }

    @Test
    void run_happyPath_readsUntilDrained() throws Exception {
        stubSinglePartitionStream();
        stubCursor();

        var data = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));

        // First call returns two records, second call returns an empty batch (caught up).
        wireMock.stubFor(get(urlMatching(".*/records.*"))
            .inScenario("consume")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"records": [
                        {"partition_key": "pk-1", "sequence_number": "seq-1", "data": "%s", "timestamp": 1000},
                        {"partition_key": "pk-1", "sequence_number": "seq-2", "data": "%s", "timestamp": 2000}
                    ], "next_partition_cursor": "cursor-2"}
                    """.formatted(data, data))
            )
            .willSetStateTo("drained"));

        wireMock.stubFor(get(urlMatching(".*/records.*"))
            .inScenario("consume")
            .whenScenarioStateIs("drained")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"records": [], "next_partition_cursor": "cursor-2"}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().build();

        var output = task.run(runContext);

        assertThat(output.getCount(), equalTo(2));
        assertThat(output.getUri(), notNullValue());

        var records = readRecords(runContext, output.getUri());
        assertThat(records.size(), equalTo(2));
        assertThat(records.get(0).getPartitionId(), equalTo("0"));
        assertThat(records.get(0).getSequenceNumber(), equalTo("seq-1"));
        assertThat(records.get(0).getPartitionKey(), equalTo("pk-1"));
        assertThat(records.get(0).getData(), equalTo("hello"));
    }

    @Test
    void run_maxRecordsReached_stopsEarly() throws Exception {
        stubSinglePartitionStream();
        stubCursor();

        var data = Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        wireMock.stubFor(get(urlMatching(".*/records.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"records": [
                        {"partition_key": "pk-1", "sequence_number": "seq-1", "data": "%s", "timestamp": 1000},
                        {"partition_key": "pk-1", "sequence_number": "seq-2", "data": "%s", "timestamp": 2000},
                        {"partition_key": "pk-1", "sequence_number": "seq-3", "data": "%s", "timestamp": 3000}
                    ], "next_partition_cursor": "cursor-2"}
                    """.formatted(data, data, data))));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().maxRecords(Property.ofValue(2)).build();

        var output = task.run(runContext);

        assertThat(output.getCount(), equalTo(2));
    }

    @Test
    void run_neitherMaxRecordsNorMaxDurationSet_throws() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = Consume.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .streamName(Property.ofValue("my-stream"))
            .build();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }

    @Test
    void run_maxRecordsExceedsHardCap_throws() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().maxRecords(Property.ofValue(Consume.MAX_RECORDS_HARD_CAP + 1)).build();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }

    @Test
    void run_maxDurationExceedsHardCap_throws() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = Consume.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .streamName(Property.ofValue("my-stream"))
            .maxDuration(Property.ofValue(Duration.ofHours(25)))
            .build();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }

    private List<Record> readRecords(io.kestra.core.runners.RunContext runContext, java.net.URI uri) throws Exception {
        try (var reader = new InputStreamReader(runContext.storage().getFile(uri), StandardCharsets.UTF_8)) {
            return FileSerde.readAll(reader, Record.class).collectList().block();
        }
    }
}
