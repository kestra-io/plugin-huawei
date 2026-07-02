package io.kestra.plugin.huawei.ces;

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

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

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

    @Test
    void evaluate_withDatapoints_returnsExecution() throws Exception {
        wireMock.stubFor(get(urlMatching(".*/metric-data.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"metric_name": "cpu_util", "datapoints": [{"average": 55.0, "timestamp": 1000, "unit": "%"}]}
                    """)));

        var trigger = Trigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
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

        var flow = buildFlow();
        var triggerContext = buildTriggerContext(trigger);
        var runContext = triggerRunContext(flow, trigger, triggerContext);
        var conditionContext = new ConditionContext(flow, null, runContext, Collections.emptyMap(), null);

        var result = trigger.evaluate(conditionContext, triggerContext);

        assertThat(result.isPresent(), is(true));
        var execution = result.get();
        assertThat(execution, notNullValue());
        assertThat(execution.getId(), notNullValue());
    }

    @Test
    void evaluate_noDatapoints_returnsEmpty() throws Exception {
        wireMock.stubFor(get(urlMatching(".*/metric-data.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"metric_name": "cpu_util", "datapoints": []}
                    """)));

        var trigger = Trigger.builder()
            .id(IdUtils.create())
            .type(Trigger.class.getName())
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

        var flow = buildFlow();
        var triggerContext = buildTriggerContext(trigger);
        var runContext = triggerRunContext(flow, trigger, triggerContext);
        var conditionContext = new ConditionContext(flow, null, runContext, Collections.emptyMap(), null);

        var result = trigger.evaluate(conditionContext, triggerContext);

        assertThat(result.isPresent(), is(false));
    }

    private Flow buildFlow() {
        return Flow.builder()
            .id("ces-trigger-test-flow")
            .namespace("company.team")
            .revision(1)
            .tasks(List.of())
            .build();
    }

    private TriggerContext buildTriggerContext(Trigger trigger) {
        return TriggerContext.builder()
            .tenantId("main")
            .namespace("company.team")
            .flowId("ces-trigger-test-flow")
            .triggerId(trigger.getId())
            .date(ZonedDateTime.now())
            .build();
    }

    private DefaultRunContext triggerRunContext(Flow flow, Trigger trigger, TriggerContext triggerContext) {
        var base = (DefaultRunContext) runContextFactory.of(flow, trigger);
        return runContextInitializer.forScheduler(base, triggerContext, trigger);
    }
}
