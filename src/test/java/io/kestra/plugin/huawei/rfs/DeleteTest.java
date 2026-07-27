package io.kestra.plugin.huawei.rfs;

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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeleteTest {

    private static final String PROJECT_ID = "test-project-abc123";
    private static final String FAKE_AK = "FAKEACCESSKEY0001";
    private static final String FAKE_SK = "fakeSecretKey0001fakeSecretKey001";
    private static final String STACK_NAME = "my-test-stack";
    private static final String STACK_ID = "stack-001";

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

    private Delete.DeleteBuilder<?, ?> baseTask() {
        return Delete.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .stackName(Property.ofValue(STACK_NAME))
            .interval(Property.ofValue(Duration.ofMillis(20)));
    }

    private String metadataBody(String status) {
        return """
            {"stack_id": "%s", "stack_name": "%s", "status": "%s"}
            """.formatted(STACK_ID, STACK_NAME, status);
    }

    private void stubMissingStack() {
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\": \"AOS.0001\", \"error_msg\": \"stack not found\"}")));
    }

    private void stubDeleteStack() {
        wireMock.stubFor(delete(urlMatching(".*/stacks/" + STACK_NAME))
            .willReturn(aResponse().withStatus(204)));
    }

    // ── Happy path ───────────────────────────────────────────────────────────────

    @Test
    void delete_happyPath_deletesExistingStack() throws Exception {
        var scenario = "deleteFlow";
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DEPLOYMENT_COMPLETE")))
            .willSetStateTo("deleting"));
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs("deleting")
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DELETION_IN_PROGRESS")))
            .willSetStateTo("gone"));
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs("gone")
            .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\": \"AOS.0001\", \"error_msg\": \"stack not found\"}")));
        stubDeleteStack();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().build();

        assertDoesNotThrow(() -> task.run(runContext));

        wireMock.verify(deleteRequestedFor(urlMatching(".*/stacks/" + STACK_NAME)));
    }

    @Test
    void delete_missingStack_defaultsToNoOp() throws Exception {
        stubMissingStack();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().build();

        task.run(runContext);

        wireMock.verify(0, deleteRequestedFor(urlMatching(".*/stacks/" + STACK_NAME)));
    }

    @Test
    void delete_waitFalse_returnsImmediatelyWithoutPolling() throws Exception {
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DEPLOYMENT_COMPLETE"))));
        stubDeleteStack();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().wait(Property.ofValue(false)).build();

        task.run(runContext);

        wireMock.verify(1, getRequestedFor(urlMatching(".*/stacks/" + STACK_NAME + "/metadata")));
        wireMock.verify(deleteRequestedFor(urlMatching(".*/stacks/" + STACK_NAME)));
    }

    // ── Failure / timeout ────────────────────────────────────────────────────────

    @Test
    void delete_missingStackWithErrorOnMissing_throwsActionableError() {
        stubMissingStack();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().errorOnMissing(Property.ofValue(true)).build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString(STACK_NAME));
    }

    @Test
    void delete_deletionFailed_throwsActionableError() {
        var scenario = "deleteFailFlow";
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DEPLOYMENT_COMPLETE")))
            .willSetStateTo("deleting"));
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs("deleting")
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DELETION_FAILED"))));
        stubDeleteStack();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("deletion failed"));
        assertThat(ex.getMessage(), containsString(STACK_NAME));
    }

    @Test
    void delete_timeout_throwsActionableError() {
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DELETION_IN_PROGRESS"))));
        stubDeleteStack();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .maxDuration(Property.ofValue(Duration.ofMillis(150)))
            .interval(Property.ofValue(Duration.ofMillis(20)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString(STACK_NAME));
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    @Test
    void delete_customEndpointWithoutProjectId_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = Delete.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .stackName(Property.ofValue(STACK_NAME))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("projectId"));
    }
}
