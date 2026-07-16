package io.kestra.plugin.huawei.dis;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
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
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealtimeTriggerTest {

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

    private RealtimeTrigger.RealtimeTriggerBuilder<?, ?> baseTrigger() {
        return RealtimeTrigger.builder()
            .id(IdUtils.create())
            .type(RealtimeTrigger.class.getName())
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .streamName(Property.ofValue("my-stream"));
    }

    private Flow buildFlow() {
        return Flow.builder()
            .tenantId("main")
            .id("dis-realtime-trigger-test-flow")
            .namespace("company.team")
            .revision(1)
            .tasks(List.of())
            .build();
    }

    private TriggerContext buildTriggerContext(RealtimeTrigger trigger) {
        return TriggerContext.builder()
            .tenantId("main")
            .namespace("company.team")
            .flowId("dis-realtime-trigger-test-flow")
            .triggerId(trigger.getId())
            .date(java.time.ZonedDateTime.now())
            .build();
    }

    private DefaultRunContext triggerRunContext(Flow flow, RealtimeTrigger trigger, TriggerContext triggerContext) {
        var base = (DefaultRunContext) runContextFactory.of(flow, trigger);
        return runContextInitializer.forScheduler(base, triggerContext, trigger);
    }

    @Test
    void evaluate_emitsOneExecutionPerRecord() throws Exception {
        var trigger = baseTrigger().build();

        var flow = buildFlow();
        var triggerContext = buildTriggerContext(trigger);
        var runContext = triggerRunContext(flow, trigger, triggerContext);
        var conditionContext = new ConditionContext(flow, null, runContext, Collections.emptyMap(), null);

        stubSinglePartitionStream();
        stubCursor();
        // Every poll round returns the same two records; the loop is stopped from the test once both
        // have been observed, so the repetition itself is irrelevant to what is being asserted.
        wireMock.stubFor(get(urlMatching(".*/records.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"records": [
                        {"partition_key": "pk-1", "sequence_number": "seq-1", "data": "%s", "timestamp": 1000},
                        {"partition_key": "pk-1", "sequence_number": "seq-2", "data": "%s", "timestamp": 2000}
                    ], "next_partition_cursor": "cursor-2"}
                    """.formatted(encode("first"), encode("second")))));

        var publisher = trigger.evaluate(conditionContext, triggerContext);

        var executions = new CopyOnWriteArrayList<Execution>();
        var latch = new CountDownLatch(2);
        var subscription = Flux.from(publisher)
            .doOnNext(execution -> {
                executions.add(execution);
                latch.countDown();
            })
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();

        try {
            assertThat(latch.await(10, TimeUnit.SECONDS), is(true));
        } finally {
            trigger.kill();
            subscription.dispose();
        }

        assertThat(executions.get(0).getTrigger().getVariables().get("sequenceNumber"), equalTo("seq-1"));
        assertThat(executions.get(0).getTrigger().getVariables().get("data"), equalTo("first"));
        assertThat(executions.get(1).getTrigger().getVariables().get("sequenceNumber"), equalTo("seq-2"));
        assertThat(executions.get(1).getTrigger().getVariables().get("data"), equalTo("second"));
    }

    @Test
    void kill_withHangingRequest_returnsWithoutBlockingIndefinitely() throws Exception {
        var trigger = baseTrigger().build();

        var flow = buildFlow();
        var triggerContext = buildTriggerContext(trigger);
        var runContext = triggerRunContext(flow, trigger, triggerContext);
        var conditionContext = new ConditionContext(flow, null, runContext, Collections.emptyMap(), null);

        stubSinglePartitionStream();
        stubCursor();
        // First call returns a record so the loop is confirmed running, then the following call hangs
        // far longer than kill()'s shutdown timeout, simulating a stuck consumeRecords() HTTP call.
        wireMock.stubFor(get(urlMatching(".*/records.*"))
            .inScenario("hang")
            .whenScenarioStateIs("Started")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"records": [
                        {"partition_key": "pk-1", "sequence_number": "seq-1", "data": "%s", "timestamp": 1000}
                    ], "next_partition_cursor": "cursor-2"}
                    """.formatted(encode("hello"))))
            .willSetStateTo("hanging"));
        wireMock.stubFor(get(urlMatching(".*/records.*"))
            .inScenario("hang")
            .whenScenarioStateIs("hanging")
            .willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(30_000)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"records": [], "next_partition_cursor": "cursor-2"}
                    """)));

        var publisher = trigger.evaluate(conditionContext, triggerContext);

        var latch = new CountDownLatch(1);
        Flux.from(publisher)
            .doOnNext(execution -> latch.countDown())
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();

        assertThat(latch.await(10, TimeUnit.SECONDS), is(true));

        var start = System.nanoTime();
        trigger.kill();
        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        // kill() must return well before the 30s hanging request completes — bounded by the shutdown timeout.
        assertThat(elapsed, lessThan(Duration.ofSeconds(20)));
    }
}
