package io.kestra.plugin.huawei.smn;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
class PublishTest {

    private static final String PROJECT_ID = "test-project-abc123";
    private static final String FAKE_AK = "FAKEACCESSKEY0001";
    private static final String FAKE_SK = "fakeSecretKey0001fakeSecretKey001";
    private static final String TOPIC_URN = "urn:smn:eu-west-101:test-project-abc123:my-topic";

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

    private void stubPublishSuccess() {
        wireMock.stubFor(WireMock.post(urlMatching(".*/notifications/topics/.*/publish"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"request_id": "req-123", "message_id": "msg-456"}
                    """)));
    }

    @Test
    void publish_happyPath_sendsPlainMessageAndReturnsIds() throws Exception {
        stubPublishSuccess();

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Publish.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .topicUrn(Property.ofValue(TOPIC_URN))
            .subject(Property.ofValue("Flow completed"))
            .message(Property.ofValue("The flow finished successfully."))
            .build();

        var output = task.run(runContext);

        assertThat(output.getMessageId(), equalTo("msg-456"));
        assertThat(output.getRequestId(), equalTo("req-123"));
        wireMock.verify(postRequestedFor(urlMatching(".*/notifications/topics/.*/publish"))
            .withRequestBody(WireMock.matchingJsonPath("$.subject", WireMock.equalTo("Flow completed")))
            .withRequestBody(WireMock.matchingJsonPath("$.message", WireMock.equalTo("The flow finished successfully."))));
    }

    @Test
    void publish_messageStructure_sendsSerializedJson() throws Exception {
        stubPublishSuccess();

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Publish.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .topicUrn(Property.ofValue(TOPIC_URN))
            .messageStructure(Property.ofValue(Map.of(
                "email", "Full report attached.",
                "sms", "ETL OK"
            )))
            .build();

        var output = task.run(runContext);

        assertThat(output.getMessageId(), equalTo("msg-456"));
        wireMock.verify(postRequestedFor(urlMatching(".*/notifications/topics/.*/publish"))
            .withRequestBody(WireMock.matchingJsonPath("$.message_structure")));
    }

    @Test
    void publish_messageAttributes_rawMapShape_sendsAttributes() throws Exception {
        // Builds the task from YAML — the same Jackson deserialization path the flow parser uses —
        // so `messageAttributes` goes through the real raw List<Map<String,Object>> shape a flow
        // produces, instead of a typed Java-builder object.
        stubPublishSuccess();

        var runContext = runContextFactory.of(Collections.emptyMap());

        var yaml = """
            id: publish
            type: io.kestra.plugin.huawei.smn.Publish
            accessKeyId: %s
            secretAccessKey: %s
            projectId: %s
            endpointOverride: %s
            topicUrn: "%s"
            message: hello
            messageAttributes:
              - name: environment
                type: STRING
                value: production
            """.formatted(FAKE_AK, FAKE_SK, PROJECT_ID, wireMockUrl(), TOPIC_URN);

        var task = JacksonMapper.ofYaml().readValue(yaml, Publish.class);

        var output = task.run(runContext);

        assertThat(output.getMessageId(), equalTo("msg-456"));
        wireMock.verify(postRequestedFor(urlMatching(".*/notifications/topics/.*/publish"))
            .withRequestBody(WireMock.matchingJsonPath("$.message_attributes[0].name", WireMock.equalTo("environment")))
            .withRequestBody(WireMock.matchingJsonPath("$.message_attributes[0].value", WireMock.equalTo("production"))));
    }

    @Test
    void publish_noModeSet_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Publish.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .topicUrn(Property.ofValue(TOPIC_URN))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("message")));
        assertThat(ex.getMessage(), is(containsString("messageStructure")));
        assertThat(ex.getMessage(), is(containsString("messageTemplateName")));
    }

    @Test
    void publish_bothMessageAndTemplate_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Publish.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .topicUrn(Property.ofValue(TOPIC_URN))
            .message(Property.ofValue("hello"))
            .messageTemplateName(Property.ofValue("my-template"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("Exactly one")));
    }

    @Test
    void publish_tagsWithoutTemplateName_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Publish.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .topicUrn(Property.ofValue(TOPIC_URN))
            .message(Property.ofValue("hello"))
            .tags(Property.ofValue(Map.of("name", "value")))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("messageTemplateName")));
    }

    @Test
    void publish_customEndpointWithoutProjectId_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Publish.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            // no projectId — a custom endpoint bypasses the SDK's automatic project discovery
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .topicUrn(Property.ofValue(TOPIC_URN))
            .message(Property.ofValue("hello"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("projectId")));
    }

    @Test
    void publish_missingAccessKey_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Publish.builder()
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .topicUrn(Property.ofValue(TOPIC_URN))
            .message(Property.ofValue("hello"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("AK/SK")));
    }

    @Test
    void publish_missingTopicUrn_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Publish.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .message(Property.ofValue("hello"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("topicUrn")));
    }

    @Test
    void publish_serviceError_wrapsWithActionableMessage() {
        wireMock.stubFor(WireMock.post(urlMatching(".*/notifications/topics/.*/publish"))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"error_code": "SMN.0001", "error_msg": "The topic does not exist."}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Publish.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .topicUrn(Property.ofValue(TOPIC_URN))
            .message(Property.ofValue("hello"))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("topicUrn")));
    }

    @Test
    void publish_defaultTemplateMissing_wrapsWithConsoleHint() {
        wireMock.stubFor(WireMock.post(urlMatching(".*/notifications/topics/.*/publish"))
            .willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"error_code": "SMN.0076", "error_msg": "Default message template not found."}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Publish.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .projectId(Property.ofValue(PROJECT_ID))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .topicUrn(Property.ofValue(TOPIC_URN))
            .messageTemplateName(Property.ofValue("kestra_qa_tpl"))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        // Surfaces SMN's own message plus the console-specific next step, not the generic hint.
        assertThat(ex.getMessage(), is(containsString("Default message template not found")));
        assertThat(ex.getMessage(), is(containsString("SMN console")));
        assertThat(ex.getMessage(), is(containsString("Protocol `Default`")));
    }

    // ── remediationHint mapping (pure, no WireMock) ──────────────────────────────

    @Test
    void remediationHint_defaultTemplateMissing_byCode_pointsToConsole() {
        var hint = Publish.remediationHint("SMN.0076", "Default message template not found.");
        assertThat(hint, is(containsString("SMN console")));
        assertThat(hint, is(containsString("Protocol `Default`")));
    }

    @Test
    void remediationHint_defaultTemplateMissing_byMessageWhenCodeAbsent() {
        // Robust to regions/gateways that omit the error code but keep the message.
        var hint = Publish.remediationHint(null, "Default message template not found.");
        assertThat(hint, is(containsString("Message Templates")));
        assertThat(hint, is(containsString("Protocol `Default`")));
    }

    @Test
    void remediationHint_templateNotFound_pointsToMessageTemplateName() {
        var hint = Publish.remediationHint("SMN.0027", "Template not found.");
        assertThat(hint, is(containsString("messageTemplateName")));
    }

    @Test
    void remediationHint_invalidMessageStructure_explainsDefaultKey() {
        var hint = Publish.remediationHint("SMN.0021", "MessageStructure is invalid.");
        assertThat(hint, is(containsString("messageStructure")));
        assertThat(hint, is(containsString("default")));
    }

    @Test
    void remediationHint_unknownCode_fallsBackToGenericHint() {
        var hint = Publish.remediationHint("SMN.9999", "Some other error.");
        assertThat(hint, is(containsString("topicUrn")));
        assertThat(hint, is(containsString("SMN FullAccess")));
    }

    // ── Integration test (guarded) ───────────────────────────────────────────────

    @Test
    @EnabledIfEnvironmentVariable(named = "SMN_TESTS", matches = "true")
    void publish_realCloud_happyPath() throws Exception {
        var topicUrn = System.getenv("SMN_TOPIC_URN");
        var region = System.getenv("SMN_REGION");
        var ak = System.getenv("HUAWEI_ACCESS_KEY");
        var sk = System.getenv("HUAWEI_SECRET_ACCESS_KEY");

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Publish.builder()
            .accessKeyId(Property.ofValue(ak))
            .secretAccessKey(Property.ofValue(sk))
            .region(Property.ofValue(region))
            .topicUrn(Property.ofValue(topicUrn))
            .message(Property.ofValue("Kestra SMN integration test"))
            .build();

        var output = task.run(runContext);

        assertThat(output.getMessageId(), is(notNullValue()));
    }
}
