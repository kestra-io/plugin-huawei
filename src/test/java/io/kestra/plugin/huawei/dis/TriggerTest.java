package io.kestra.plugin.huawei.dis;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.runners.RunContextInitializer;
import io.kestra.core.utils.IdUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TriggerTest {

    private static final String PROJECT_ID = "test-project-abc123";
    private static final String FAKE_AK = "FAKEACCESSKEY0001";
    private static final String FAKE_SK = "fakeSecretKey0001fakeSecretKey001";

    private WireMockServer wireMock;

    @Inject
    RunContextFactory runContextFactory;

    @Inject
    RunContextInitializer runContextInitializer;

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
                    {"partition_cursor": "some-cursor"}
                    """)));
    }

    private String encode(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void evaluate_overlappingPolls_doesNotRedeliverRecords() throws Exception {
        var trigger = Trigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .streamName(Property.ofValue("my-stream"))
            .maxRecords(Property.ofValue(100))
            .build();

        var flow = buildFlow();
        var triggerContext = buildTriggerContext(trigger);
        var runContext = triggerRunContext(flow, trigger, triggerContext);
        var conditionContext = new ConditionContext(flow, null, runContext, Collections.emptyMap(), null);

        // First poll: two records, then a drained round.
        stubSinglePartitionStream();
        stubCursor();
        wireMock.stubFor(get(urlMatching(".*/records.*"))
            .inScenario("poll-1")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"records": [
                        {"partition_key": "pk-1", "sequence_number": "seq-1", "data": "%s", "timestamp": 1000},
                        {"partition_key": "pk-1", "sequence_number": "seq-2", "data": "%s", "timestamp": 2000}
                    ], "next_partition_cursor": "cursor-2"}
                    """.formatted(encode("first"), encode("second")))
            )
            .willSetStateTo("drained-1"));
        wireMock.stubFor(get(urlMatching(".*/records.*"))
            .inScenario("poll-1")
            .whenScenarioStateIs("drained-1")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"records": [], "next_partition_cursor": "cursor-2"}
                    """)));

        var firstResult = trigger.evaluate(conditionContext, triggerContext);

        assertThat(firstResult.isPresent(), is(true));
        assertThat(firstResult.get().getTrigger().getVariables().get("count"), is(2));

        // Second poll: only one new record should be delivered (the watermark resumes after seq-2).
        wireMock.resetAll();
        stubSinglePartitionStream();
        stubCursor();
        wireMock.stubFor(get(urlMatching(".*/records.*"))
            .inScenario("poll-2")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"records": [
                        {"partition_key": "pk-1", "sequence_number": "seq-3", "data": "%s", "timestamp": 3000}
                    ], "next_partition_cursor": "cursor-3"}
                    """.formatted(encode("third")))
            )
            .willSetStateTo("drained-2"));
        wireMock.stubFor(get(urlMatching(".*/records.*"))
            .inScenario("poll-2")
            .whenScenarioStateIs("drained-2")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"records": [], "next_partition_cursor": "cursor-3"}
                    """)));

        var secondResult = trigger.evaluate(conditionContext, triggerContext);

        assertThat(secondResult.isPresent(), is(true));
        assertThat(secondResult.get().getTrigger().getVariables().get("count"), is(1));
    }

    @Test
    void evaluate_noNewRecords_returnsEmpty() throws Exception {
        var trigger = Trigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .streamName(Property.ofValue("my-stream"))
            .maxRecords(Property.ofValue(100))
            .build();

        stubSinglePartitionStream();
        stubCursor();
        wireMock.stubFor(get(urlMatching(".*/records.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"records": [], "next_partition_cursor": "cursor-1"}
                    """)));

        var flow = buildFlow();
        var triggerContext = buildTriggerContext(trigger);
        var runContext = triggerRunContext(flow, trigger, triggerContext);
        var conditionContext = new ConditionContext(flow, null, runContext, Collections.emptyMap(), null);

        var result = trigger.evaluate(conditionContext, triggerContext);

        assertThat(result.isPresent(), is(false));
    }

    private Flow buildFlow() {
        return Flow.builder()
            .tenantId("main")
            .id("dis-trigger-test-flow")
            .namespace("company.team")
            .revision(1)
            .tasks(List.of())
            .build();
    }

    private TriggerContext buildTriggerContext(Trigger trigger) {
        return TriggerContext.builder()
            .tenantId("main")
            .namespace("company.team")
            .flowId("dis-trigger-test-flow")
            .triggerId(trigger.getId())
            .date(java.time.ZonedDateTime.now())
            .build();
    }

    private DefaultRunContext triggerRunContext(Flow flow, Trigger trigger, TriggerContext triggerContext) {
        var base = (DefaultRunContext) runContextFactory.of(flow, trigger);
        return runContextInitializer.forScheduler(base, triggerContext, trigger);
    }
}
