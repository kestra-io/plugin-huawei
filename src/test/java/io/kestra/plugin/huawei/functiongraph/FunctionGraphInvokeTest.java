package io.kestra.plugin.huawei.functiongraph;

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

import java.util.Collections;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FunctionGraphInvokeTest {

    private static final String PROJECT_ID = "test-project-abc123";
    private static final String FUNCTION_URN = "urn:fss:eu-west-101:" + PROJECT_ID + ":function:default:my-fn:latest";
    // URL-encoded colon-separated URN path segment used by the SDK
    private static final String INVOCATIONS_PATH = "/v2/" + PROJECT_ID + "/fgs/functions/" + FUNCTION_URN + "/invocations";
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

    // ── Happy path ───────────────────────────────────────────────────────────────

    @Test
    void invoke_happyPath_returnsOutputUri() throws Exception {
        wireMock.stubFor(post(urlMatching(".*/invocations"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"result": "{\\"message\\":\\"hello world\\"}","status": 0,"request_id": "req-abc-123"}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Invoke.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .functionUrn(Property.ofValue(FUNCTION_URN))
            .build();

        var output = task.run(runContext);

        assertThat(output.getUri(), is(notNullValue()));
        assertThat(output.getContentLength(), is(notNullValue()));
        assertThat(output.getStatusCode(), equalTo(200));
    }

    @Test
    void invoke_emptyResponse_writesZeroByteFile() throws Exception {
        wireMock.stubFor(post(urlMatching(".*/invocations"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"status": 0,"request_id": "req-empty-001"}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Invoke.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .functionUrn(Property.ofValue(FUNCTION_URN))
            .build();

        var output = task.run(runContext);

        assertThat(output.getUri(), is(notNullValue()));
        assertThat(output.getContentLength(), equalTo(0L));
    }

    // ── Payload forwarding ───────────────────────────────────────────────────────

    @Test
    void invoke_withPayload_sendsPayloadInBody() throws Exception {
        wireMock.stubFor(post(urlMatching(".*/invocations"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"result": "ok","status": 0,"request_id": "req-payload-001"}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Invoke.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .functionUrn(Property.ofValue(FUNCTION_URN))
            .functionPayload(Property.ofValue(Map.of("env", "test", "date", "2024-01-01")))
            .build();

        task.run(runContext);

        wireMock.verify(postRequestedFor(urlMatching(".*/invocations"))
            .withRequestBody(WireMock.matchingJsonPath("$.env", WireMock.equalTo("test")))
            .withRequestBody(WireMock.matchingJsonPath("$.date", WireMock.equalTo("2024-01-01"))));
    }

    // ── Function-level error ─────────────────────────────────────────────────────

    @Test
    void invoke_functionError_throwsFunctionGraphInvokeException() {
        wireMock.stubFor(post(urlMatching(".*/invocations"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"result": "Unhandled exception in user code","status": 1,"request_id": "req-err-001"}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Invoke.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .functionUrn(Property.ofValue(FUNCTION_URN))
            .build();

        var ex = assertThrows(FunctionGraphInvokeException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString(FUNCTION_URN));
        assertThat(ex.getMessage(), containsString("function-level error"));
        assertThat(ex.getMessage(), containsString("LTS"));
    }

    // ── HTTP 4xx error ───────────────────────────────────────────────────────────

    @Test
    void invoke_http404_throwsFunctionGraphInvokeException() {
        wireMock.stubFor(post(urlMatching(".*/invocations"))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"error_code": "FSS.0404","error_msg": "function not found"}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Invoke.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .functionUrn(Property.ofValue(FUNCTION_URN))
            .build();

        var ex = assertThrows(FunctionGraphInvokeException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("404"));
        assertThat(ex.getMessage(), containsString(FUNCTION_URN));
    }

    // ── Missing credentials ──────────────────────────────────────────────────────

    @Test
    void invoke_missingAccessKey_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Invoke.builder()
            // accessKeyId intentionally not set
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .functionUrn(Property.ofValue(FUNCTION_URN))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("AK/SK"));
    }

    @Test
    void invoke_missingSecretKey_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Invoke.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            // secretAccessKey intentionally not set
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .functionUrn(Property.ofValue(FUNCTION_URN))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("secretAccessKey"));
    }

    // ── Endpoint resolution ──────────────────────────────────────────────────────

    @Test
    void invoke_noEndpointNoRegion_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Invoke.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            // no endpointOverride, no region
            .functionUrn(Property.ofValue(FUNCTION_URN))
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }

    // ── Integration tests (guarded) ──────────────────────────────────────────────

    @Test
    @EnabledIfEnvironmentVariable(named = "FUNCTIONGRAPH_TESTS", matches = "true")
    void invoke_realCloud_happyPath() throws Exception {
        var urn = System.getenv("FUNCTIONGRAPH_URN");
        var region = System.getenv("FUNCTIONGRAPH_REGION");
        var projectId = System.getenv("FUNCTIONGRAPH_PROJECT_ID");
        var ak = System.getenv("HUAWEI_ACCESS_KEY");
        var sk = System.getenv("HUAWEI_SECRET_ACCESS_KEY");

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Invoke.builder()
            .accessKeyId(Property.ofValue(ak))
            .secretAccessKey(Property.ofValue(sk))
            .projectId(Property.ofValue(projectId))
            .region(Property.ofValue(region))
            .functionUrn(Property.ofValue(urn))
            .build();

        var output = task.run(runContext);

        assertThat(output.getUri(), is(notNullValue()));
        assertThat(output.getStatusCode(), equalTo(200));
    }
}
