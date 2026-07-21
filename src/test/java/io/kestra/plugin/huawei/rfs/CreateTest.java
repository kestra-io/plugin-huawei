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
import java.util.Map;

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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreateTest {

    private static final String PROJECT_ID = "test-project-abc123";
    private static final String FAKE_AK = "FAKEACCESSKEY0001";
    private static final String FAKE_SK = "fakeSecretKey0001fakeSecretKey001";
    private static final String STACK_NAME = "my-test-stack";
    private static final String STACK_ID = "stack-001";
    private static final String DEPLOYMENT_ID = "deployment-001";

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

    private Create.CreateBuilder<?, ?> baseTask() {
        return Create.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .stackName(Property.ofValue(STACK_NAME))
            .templateBody(Property.ofValue("resource \"random_id\" \"id\" {}"))
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

    /** Absent stack -> creates -> in-progress -> complete. */
    private void stubNewStackDeployFlow() {
        var scenario = "createFlow";
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\": \"AOS.0001\", \"error_msg\": \"stack not found\"}"))
            .willSetStateTo("created"));
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs("created")
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DEPLOYMENT_IN_PROGRESS")))
            .willSetStateTo("in_progress"));
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs("in_progress")
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DEPLOYMENT_COMPLETE"))));
    }

    /** Existing stack (previously deployed) -> deploys an update -> in-progress -> complete. */
    private void stubExistingStackDeployFlow() {
        var scenario = "updateFlow";
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DEPLOYMENT_COMPLETE")))
            .willSetStateTo("probed"));
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs("probed")
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DEPLOYMENT_IN_PROGRESS")))
            .willSetStateTo("in_progress"));
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs("in_progress")
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DEPLOYMENT_COMPLETE"))));
    }

    private void stubCreateStack() {
        wireMock.stubFor(post(urlMatching(".*/stacks"))
            .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                .withBody("""
                    {"stack_id": "%s", "deployment_id": "%s"}
                    """.formatted(STACK_ID, DEPLOYMENT_ID))));
    }

    private void stubDeployStack() {
        wireMock.stubFor(post(urlMatching(".*/stacks/" + STACK_NAME + "/deployments"))
            .willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json")
                .withBody("""
                    {"deployment_id": "%s"}
                    """.formatted(DEPLOYMENT_ID))));
    }

    private void stubOutputs() {
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/outputs.*"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("""
                    {"outputs": [{"name": "bucket_name", "value": "my-bucket", "sensitive": false}]}
                    """)));
    }

    // ── Happy path ───────────────────────────────────────────────────────────────

    @Test
    void create_happyPath_createsNewStackAndReturnsOutputs() throws Exception {
        stubNewStackDeployFlow();
        stubCreateStack();
        stubOutputs();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var output = baseTask().build().run(runContext);

        assertThat(output.getStackId(), equalTo(STACK_ID));
        assertThat(output.getStackName(), equalTo(STACK_NAME));
        assertThat(output.getDeploymentId(), equalTo(DEPLOYMENT_ID));
        assertThat(output.getStatus(), equalTo("DEPLOYMENT_COMPLETE"));
        assertThat(output.getOutputs().get("bucket_name"), equalTo("my-bucket"));

        wireMock.verify(postRequestedFor(urlMatching(".*/stacks")));
    }

    @Test
    void create_existingStack_deploysUpdate() throws Exception {
        stubExistingStackDeployFlow();
        stubDeployStack();
        stubOutputs();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var output = baseTask().build().run(runContext);

        assertThat(output.getStackId(), equalTo(STACK_ID));
        assertThat(output.getDeploymentId(), equalTo(DEPLOYMENT_ID));
        assertThat(output.getStatus(), equalTo("DEPLOYMENT_COMPLETE"));

        wireMock.verify(postRequestedFor(urlMatching(".*/stacks/" + STACK_NAME + "/deployments")));
        wireMock.verify(0, postRequestedFor(urlMatching(".*/stacks$")));
    }

    @Test
    void create_waitFalse_returnsImmediatelyWithEmptyOutputs() throws Exception {
        stubMissingStack();
        stubCreateStack();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().wait(Property.ofValue(false)).build();

        var output = task.run(runContext);

        assertThat(output.getStackId(), equalTo(STACK_ID));
        assertThat(output.getDeploymentId(), equalTo(DEPLOYMENT_ID));
        assertThat(output.getStatus(), is(nullValue()));
        assertThat(output.getOutputs().isEmpty(), is(true));

        wireMock.verify(0, getRequestedFor(urlMatching(".*/outputs.*")));
    }

    // ── Failure / timeout ────────────────────────────────────────────────────────

    @Test
    void create_deploymentFailed_throwsActionableError() {
        var scenario = "failFlow";
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\": \"AOS.0001\", \"error_msg\": \"stack not found\"}"))
            .willSetStateTo("created"));
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs("created")
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DEPLOYMENT_FAILED"))));
        stubCreateStack();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("DEPLOYMENT_FAILED"));
        assertThat(ex.getMessage(), containsString(STACK_NAME));
    }

    @Test
    void create_timeout_throwsActionableError() {
        var scenario = "timeoutFlow";
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                .withBody("{\"error_code\": \"AOS.0001\", \"error_msg\": \"stack not found\"}"))
            .willSetStateTo("created"));
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .inScenario(scenario).whenScenarioStateIs("created")
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DEPLOYMENT_IN_PROGRESS"))));
        stubCreateStack();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .maxDuration(Property.ofValue(Duration.ofMillis(150)))
            .interval(Property.ofValue(Duration.ofMillis(20)))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString(STACK_NAME));
        assertThat(ex.getMessage(), containsString("terminal"));
    }

    @Test
    void create_existingStackInProgress_throwsWithoutDeploying() {
        wireMock.stubFor(get(urlMatching(".*/stacks/" + STACK_NAME + "/metadata"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(metadataBody("DEPLOYMENT_IN_PROGRESS"))));
        stubDeployStack();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString(STACK_NAME));
        assertThat(ex.getMessage(), containsString("DEPLOYMENT_IN_PROGRESS"));

        wireMock.verify(0, postRequestedFor(urlMatching(".*/stacks/" + STACK_NAME + "/deployments")));
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    @Test
    void create_bothTemplateSources_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask().templateUri(Property.ofValue("obs://bucket/template.tf")).build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("templateBody"));
        assertThat(ex.getMessage(), containsString("templateUri"));
    }

    @Test
    void create_noTemplateSource_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = Create.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .stackName(Property.ofValue(STACK_NAME))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("templateBody"));
    }

    @Test
    void create_multipleVarsSources_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = baseTask()
            .vars(Property.ofValue(Map.of("env", "prod")))
            .varsBody(Property.ofValue("env = \"prod\""))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("vars"));
    }

    @Test
    void create_customEndpointWithoutProjectId_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = Create.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .stackName(Property.ofValue(STACK_NAME))
            .templateBody(Property.ofValue("resource \"random_id\" \"id\" {}"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("projectId"));
    }

    @Test
    void create_missingAccessKey_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = Create.builder()
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .stackName(Property.ofValue(STACK_NAME))
            .templateBody(Property.ofValue("resource \"random_id\" \"id\" {}"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("AK/SK"));
    }
}
