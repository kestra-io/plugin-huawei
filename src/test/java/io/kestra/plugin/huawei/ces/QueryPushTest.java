package io.kestra.plugin.huawei.ces;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
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
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryPushTest {

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

    // ── Push happy path ──────────────────────────────────────────────────────────

    @Test
    void push_happyPath_sendsMetricAndReturnsCount() throws Exception {
        wireMock.stubFor(post(urlMatching(".*/metric-data"))
            .willReturn(aResponse().withStatus(204)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Push.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .namespace(Property.ofValue("MyApp.Custom"))
            .metrics(Property.ofValue(List.of(
                Push.MetricValue.builder()
                    .metricName(Property.ofValue("queue_depth"))
                    .value(Property.ofValue(42.0))
                    .unit(Property.ofValue("Count"))
                    .dimensions(Property.ofValue(List.of(
                        Dimension.builder().name(Property.ofValue("queue_name")).value(Property.ofValue("orders")).build()
                    )))
                    .build()
            )))
            .build();

        var output = task.run(runContext);

        assertThat(output.getCount(), equalTo(1));
        wireMock.verify(postRequestedFor(urlMatching(".*/metric-data"))
            .withRequestBody(WireMock.matchingJsonPath("$[0].metric.namespace", WireMock.equalTo("MyApp.Custom")))
            .withRequestBody(WireMock.matchingJsonPath("$[0].metric.metric_name", WireMock.equalTo("queue_depth")))
            .withRequestBody(WireMock.matchingJsonPath("$[0].metric.dimensions[0].name", WireMock.equalTo("queue_name"))));
    }

    @Test
    void push_sysNamespace_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Push.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .namespace(Property.ofValue("SYS.ECS"))
            .metrics(Property.ofValue(List.of(
                Push.MetricValue.builder()
                    .metricName(Property.ofValue("queue_depth"))
                    .value(Property.ofValue(42.0))
                    .build()
            )))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("SYS.")));
    }

    @Test
    void push_tooManyDimensions_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var dims = List.of(
            Dimension.builder().name(Property.ofValue("a")).value(Property.ofValue("1")).build(),
            Dimension.builder().name(Property.ofValue("b")).value(Property.ofValue("2")).build(),
            Dimension.builder().name(Property.ofValue("c")).value(Property.ofValue("3")).build(),
            Dimension.builder().name(Property.ofValue("d")).value(Property.ofValue("4")).build()
        );

        var task = Push.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .namespace(Property.ofValue("MyApp.Custom"))
            .metrics(Property.ofValue(List.of(
                Push.MetricValue.builder()
                    .metricName(Property.ofValue("queue_depth"))
                    .value(Property.ofValue(42.0))
                    .dimensions(Property.ofValue(dims))
                    .build()
            )))
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }

    @Test
    void push_batchLargerThanTen_chunksIntoMultipleRequests() throws Exception {
        wireMock.stubFor(post(urlMatching(".*/metric-data"))
            .willReturn(aResponse().withStatus(204)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var metrics = IntStream.range(0, 15)
            .mapToObj(i -> Push.MetricValue.builder()
                .metricName(Property.ofValue("metric_" + i))
                .value(Property.ofValue((double) i))
                .build())
            .toList();

        var task = Push.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .namespace(Property.ofValue("MyApp.Custom"))
            .metrics(Property.ofValue(metrics))
            .build();

        var output = task.run(runContext);

        assertThat(output.getCount(), equalTo(15));
        wireMock.verify(2, postRequestedFor(urlMatching(".*/metric-data")));
    }

    // ── Query happy path ─────────────────────────────────────────────────────────

    @Test
    void query_happyPath_returnsSortedSeries() throws Exception {
        wireMock.stubFor(get(urlMatching(".*/metric-data.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "metric_name": "cpu_util",
                      "datapoints": [
                        {"average": 12.5, "timestamp": 2000, "unit": "%"},
                        {"average": 10.0, "timestamp": 1000, "unit": "%"}
                      ]
                    }
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Query.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .namespace(Property.ofValue("SYS.ECS"))
            .metricName(Property.ofValue("cpu_util"))
            .dimensions(Property.ofValue(List.of(
                Dimension.builder().name(Property.ofValue("instance_id")).value(Property.ofValue("abc123")).build()
            )))
            .statistic(Property.ofValue(Query.Statistic.AVERAGE))
            .period(Property.ofValue(Query.Period.FIVE_MINUTES))
            .window(Property.ofValue(Duration.ofHours(1)))
            .build();

        var output = task.run(runContext);

        assertThat(output.getCount(), equalTo(2));
        assertThat(output.getSeries().get(0).getValue(), equalTo(10.0));
        assertThat(output.getSeries().get(1).getValue(), equalTo(12.5));
        wireMock.verify(getRequestedFor(urlMatching(".*/metric-data.*"))
            .withQueryParam("namespace", WireMock.equalTo("SYS.ECS"))
            .withQueryParam("metric_name", WireMock.equalTo("cpu_util"))
            .withQueryParam("dim.0", WireMock.equalTo("instance_id,abc123"))
            .withQueryParam("filter", WireMock.equalTo("average")));
    }

    @Test
    void query_noDatapoints_returnsZeroCount() throws Exception {
        wireMock.stubFor(get(urlMatching(".*/metric-data.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"metric_name": "cpu_util", "datapoints": []}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Query.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .namespace(Property.ofValue("SYS.ECS"))
            .metricName(Property.ofValue("cpu_util"))
            .dimensions(Property.ofValue(List.of(
                Dimension.builder().name(Property.ofValue("instance_id")).value(Property.ofValue("abc123")).build()
            )))
            .build();

        var output = task.run(runContext);

        assertThat(output.getCount(), equalTo(0));
        assertThat(output.getSeries(), is(notNullValue()));
        assertThat(output.getSeries().isEmpty(), equalTo(true));
    }

    @Test
    void query_invalidNamespaceFormat_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Query.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .namespace(Property.ofValue("NotANamespace"))
            .metricName(Property.ofValue("cpu_util"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("service.item")));
    }

    @Test
    void query_tooManyDimensions_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var dims = List.of(
            Dimension.builder().name(Property.ofValue("a")).value(Property.ofValue("1")).build(),
            Dimension.builder().name(Property.ofValue("b")).value(Property.ofValue("2")).build(),
            Dimension.builder().name(Property.ofValue("c")).value(Property.ofValue("3")).build(),
            Dimension.builder().name(Property.ofValue("d")).value(Property.ofValue("4")).build()
        );

        var task = Query.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .namespace(Property.ofValue("SYS.ECS"))
            .metricName(Property.ofValue("cpu_util"))
            .dimensions(Property.ofValue(dims))
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }

    @Test
    void query_missingAccessKey_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Query.builder()
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .namespace(Property.ofValue("SYS.ECS"))
            .metricName(Property.ofValue("cpu_util"))
            .dimensions(Property.ofValue(List.of(
                Dimension.builder().name(Property.ofValue("instance_id")).value(Property.ofValue("abc123")).build()
            )))
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
            .namespace(Property.ofValue("SYS.ECS"))
            .metricName(Property.ofValue("cpu_util"))
            .dimensions(Property.ofValue(List.of(
                Dimension.builder().name(Property.ofValue("instance_id")).value(Property.ofValue("abc123")).build()
            )))
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }

    // ── Integration tests (guarded) ──────────────────────────────────────────────

    @Test
    @EnabledIfEnvironmentVariable(named = "CES_TESTS", matches = "true")
    void query_realCloud_happyPath() throws Exception {
        var namespace = System.getenv("CES_NAMESPACE");
        var metricName = System.getenv("CES_METRIC_NAME");
        var region = System.getenv("CES_REGION");
        var ak = System.getenv("HUAWEI_ACCESS_KEY");
        var sk = System.getenv("HUAWEI_SECRET_ACCESS_KEY");

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Query.builder()
            .accessKeyId(Property.ofValue(ak))
            .secretAccessKey(Property.ofValue(sk))
            .region(Property.ofValue(region))
            .namespace(Property.ofValue(namespace))
            .metricName(Property.ofValue(metricName))
            .build();

        var output = task.run(runContext);

        assertThat(output.getCount(), is(notNullValue()));
    }
}
