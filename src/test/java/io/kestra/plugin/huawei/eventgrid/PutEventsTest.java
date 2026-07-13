package io.kestra.plugin.huawei.eventgrid;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PutEventsTest {

    private static final String PROJECT_ID = "test-project-abc123";
    private static final String CHANNEL_ID = "channel-001";
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

    private PutEvents.PutEventsBuilder<?, ?> baseTask() {
        return PutEvents.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .channelId(Property.ofValue(CHANNEL_ID));
    }

    private String putEventsPath() {
        return "/v1/" + PROJECT_ID + "/channels/" + CHANNEL_ID + "/events";
    }

    // ── Happy path ───────────────────────────────────────────────────────────────

    @Test
    void putEvents_happyPath_returnsSuccessAndStoresResults() throws Exception {
        wireMock.stubFor(post(urlMatching(".*" + putEventsPath()))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"failed_count": 0, "events": [{"event_id": "evt-1"}, {"event_id": "evt-2"}]}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .events(List.of(
                PutEvents.Event.builder()
                    .source(Property.ofValue("my-order-service"))
                    .type(Property.ofValue("com.mycompany.order.created"))
                    .data(Property.ofValue(Map.of("orderId", 12345)))
                    .build(),
                PutEvents.Event.builder()
                    .source(Property.ofValue("my-order-service"))
                    .type(Property.ofValue("com.mycompany.order.cancelled"))
                    .build()
            ))
            .build();

        var output = task.run(runContext);

        assertThat(output.getEventCount(), equalTo(2));
        assertThat(output.getFailedEventCount(), equalTo(0));
        assertThat(output.getUri(), is(notNullValue()));
        assertThat(output.finalState(), is(java.util.Optional.empty()));

        List<Map> results;
        try (var reader = new InputStreamReader(runContext.storage().getFile(output.getUri()), StandardCharsets.UTF_8)) {
            results = FileSerde.readAll(reader, Map.class).collectList().block();
        }
        assertThat(results.size(), equalTo(2));
        assertThat(results.get(0).get("eventId"), equalTo("evt-1"));
        assertThat(results.get(1).get("eventId"), equalTo("evt-2"));

        wireMock.verify(postRequestedFor(urlMatching(".*" + putEventsPath()))
            .withRequestBody(WireMock.matchingJsonPath("$.events[0].source", WireMock.equalTo("my-order-service")))
            .withRequestBody(WireMock.matchingJsonPath("$.events[0].type", WireMock.equalTo("com.mycompany.order.created")))
            .withRequestBody(WireMock.matchingJsonPath("$.events[0].specversion", WireMock.equalTo("1.0")))
            .withRequestBody(WireMock.matchingJsonPath("$.events[0].id"))
            .withRequestBody(WireMock.matchingJsonPath("$.events[1].type", WireMock.equalTo("com.mycompany.order.cancelled"))));
    }

    @Test
    void putEvents_fromStorageUri_readsIonEvents() throws Exception {
        wireMock.stubFor(post(urlMatching(".*" + putEventsPath()))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"failed_count": 0, "events": [{"event_id": "evt-1"}]}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        try (var stream = new FileOutputStream(tempFile)) {
            FileSerde.write(stream, PutEvents.Event.builder()
                .source(Property.ofValue("my-order-service"))
                .type(Property.ofValue("com.mycompany.order.created"))
                .build());
        }
        var storedUri = runContext.storage().putFile(tempFile);

        var task = baseTask().events(storedUri.toString()).build();

        var output = task.run(runContext);

        assertThat(output.getEventCount(), equalTo(1));
        assertThat(output.getFailedEventCount(), equalTo(0));
    }

    // ── Partial failure ──────────────────────────────────────────────────────────

    @Test
    void putEvents_partialFailure_failOnUnsuccessfulTrue_throws() throws Exception {
        wireMock.stubFor(post(urlMatching(".*" + putEventsPath()))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"failed_count": 1, "events": [{"event_id": "evt-1"}, {"error_code": "EG.4001", "error_msg": "invalid channel"}]}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .events(List.of(
                PutEvents.Event.builder().source(Property.ofValue("svc")).type(Property.ofValue("type.a")).build(),
                PutEvents.Event.builder().source(Property.ofValue("svc")).type(Property.ofValue("type.b")).build()
            ))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("1 of 2")));
        assertThat(ex.getMessage(), is(containsString("EG.4001")));
    }

    @Test
    void putEvents_partialFailure_failOnUnsuccessfulFalse_returnsWarningState() throws Exception {
        wireMock.stubFor(post(urlMatching(".*" + putEventsPath()))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"failed_count": 1, "events": [{"event_id": "evt-1"}, {"error_code": "EG.4001", "error_msg": "invalid channel"}]}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .failOnUnsuccessfulEvents(Property.ofValue(false))
            .events(List.of(
                PutEvents.Event.builder().source(Property.ofValue("svc")).type(Property.ofValue("type.a")).build(),
                PutEvents.Event.builder().source(Property.ofValue("svc")).type(Property.ofValue("type.b")).build()
            ))
            .build();

        var output = task.run(runContext);

        assertThat(output.getEventCount(), equalTo(2));
        assertThat(output.getFailedEventCount(), equalTo(1));
        assertThat(output.finalState(), is(java.util.Optional.of(State.Type.WARNING)));

        List<Map> results;
        try (var reader = new InputStreamReader(runContext.storage().getFile(output.getUri()), StandardCharsets.UTF_8)) {
            results = FileSerde.readAll(reader, Map.class).collectList().block();
        }
        assertThat(results.get(1).get("errorCode"), equalTo("EG.4001"));
        assertThat(results.get(1).get("errorMsg"), equalTo("invalid channel"));
        assertThat(results.get(1).get("eventId"), is(nullValue()));
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    @Test
    void putEvents_missingSource_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .events(List.of(PutEvents.Event.builder().type(Property.ofValue("type.a")).build()))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("events[0].source is required")));
    }

    @Test
    void putEvents_missingType_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .events(List.of(PutEvents.Event.builder().source(Property.ofValue("svc")).build()))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("events[0].type is required")));
    }

    @Test
    void putEvents_emptyEventsList_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().events(List.of()).build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("at least one event")));
    }

    @Test
    void putEvents_customEndpointWithoutProjectId_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = PutEvents.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .channelId(Property.ofValue(CHANNEL_ID))
            .events(List.of(PutEvents.Event.builder().source(Property.ofValue("svc")).type(Property.ofValue("type.a")).build()))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("projectId")));
    }

    @Test
    void putEvents_missingAccessKey_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = PutEvents.builder()
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .channelId(Property.ofValue(CHANNEL_ID))
            .events(List.of(PutEvents.Event.builder().source(Property.ofValue("svc")).type(Property.ofValue("type.a")).build()))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("AK/SK")));
    }

    @Test
    void putEvents_noEndpointNoRegion_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = PutEvents.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .channelId(Property.ofValue(CHANNEL_ID))
            .events(List.of(PutEvents.Event.builder().source(Property.ofValue("svc")).type(Property.ofValue("type.a")).build()))
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }

    // ── Integration test (guarded) ────────────────────────────────────────────────

    @Test
    @EnabledIfEnvironmentVariable(named = "EVENTGRID_TESTS", matches = "true")
    void putEvents_realCloud_happyPath() throws Exception {
        var region = System.getenv("EVENTGRID_REGION");
        var ak = System.getenv("HUAWEI_ACCESS_KEY");
        var sk = System.getenv("HUAWEI_SECRET_ACCESS_KEY");
        var projectId = System.getenv("HUAWEI_PROJECT_ID");
        var channelId = System.getenv("EVENTGRID_CHANNEL_ID");

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = PutEvents.builder()
            .accessKeyId(Property.ofValue(ak))
            .secretAccessKey(Property.ofValue(sk))
            .region(Property.ofValue(region))
            .projectId(Property.ofValue(projectId))
            .channelId(Property.ofValue(channelId))
            .events(List.of(
                PutEvents.Event.builder()
                    .source(Property.ofValue("kestra-plugin-huawei-test"))
                    .type(Property.ofValue("io.kestra.test.event"))
                    .data(Property.ofValue(Map.of("message", "hello from PutEventsTest")))
                    .build()
            ))
            .build();

        var output = task.run(runContext);

        assertThat(output.getEventCount(), equalTo(1));
        assertThat(output.getFailedEventCount(), equalTo(0));
    }
}
