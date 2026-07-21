package io.kestra.plugin.huawei.swr;

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

import java.util.Base64;
import java.util.Collections;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetAuthTokenTest {

    private static final String FAKE_AK = "FAKEACCESSKEY0001";
    private static final String FAKE_SK = "fakeSecretKey0001fakeSecretKey001";
    private static final String REGISTRY_HOST = "swr.eu-west-101.myhuaweicloud.com";
    private static final String USERNAME = "eu-west-101@" + FAKE_AK;
    private static final String PASSWORD = "tokenSecretValue123";

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

    private static String encodedAuth() {
        return Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD).getBytes());
    }

    private void stubCreateSecretSuccess() {
        wireMock.stubFor(WireMock.post(urlMatching(".*/v2/manage/utils/secret.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("X-Swr-Dockerlogin", "docker login -u " + USERNAME + " -p " + PASSWORD + " " + REGISTRY_HOST)
                .withHeader("X-Swr-Expireat", "2026-07-22T10:00:00Z")
                .withBody("""
                    {"auths": {"%s": {"auth": "%s"}}}
                    """.formatted(REGISTRY_HOST, encodedAuth()))));
    }

    @Test
    void getAuthToken_happyPath_decodesAuthsMapAndReturnsCredential() throws Exception {
        stubCreateSecretSuccess();

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetAuthToken.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .region(Property.ofValue("eu-west-101"))
            .build();

        var output = task.run(runContext);

        assertThat(output.getUsername(), equalTo(USERNAME));
        assertThat(output.getPassword().getValue(), equalTo(PASSWORD));
        assertThat(output.getRegistry(), equalTo(REGISTRY_HOST));
        assertThat(output.getExpiry(), equalTo("2026-07-22T10:00:00Z"));
        wireMock.verify(postRequestedFor(urlMatching(".*/v2/manage/utils/secret.*")));
    }

    @Test
    void getAuthToken_fromYaml_rawTaskShape_parsesAndRuns() throws Exception {
        // Builds the task from YAML — the same Jackson deserialization path the flow parser uses —
        // so the plugin schema (Property<String> fields, no jakarta constraints on Property<Integer>)
        // is exercised the way a real flow would produce it, instead of via the Java builder.
        stubCreateSecretSuccess();

        var runContext = runContextFactory.of(Collections.emptyMap());

        var yaml = """
            id: get_auth_token
            type: io.kestra.plugin.huawei.swr.GetAuthToken
            accessKeyId: %s
            secretAccessKey: %s
            endpointOverride: %s
            region: eu-west-101
            projectName: eu-west-101
            """.formatted(FAKE_AK, FAKE_SK, wireMockUrl());

        var task = JacksonMapper.ofYaml().readValue(yaml, GetAuthToken.class);

        var output = task.run(runContext);

        assertThat(output.getUsername(), equalTo(USERNAME));
        assertThat(output.getRegistry(), equalTo(REGISTRY_HOST));
    }

    @Test
    void getAuthToken_projectNameOmitted_defaultsToRegionQueryParam() throws Exception {
        stubCreateSecretSuccess();

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetAuthToken.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .region(Property.ofValue("eu-west-101"))
            .build();

        task.run(runContext);

        wireMock.verify(postRequestedFor(urlMatching(".*/v2/manage/utils/secret.*"))
            .withQueryParam("projectname", WireMock.equalTo("eu-west-101")));
    }

    @Test
    void getAuthToken_customEndpointWithoutProjectId_succeeds() throws Exception {
        // SWR's createSecret path has no {project_id} segment, unlike CES/SMN/EventGrid/DLI —
        // a custom endpoint must work without `projectId` set.
        stubCreateSecretSuccess();

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetAuthToken.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            // no region, no projectId
            .build();

        var output = task.run(runContext);

        assertThat(output.getUsername(), equalTo(USERNAME));
    }

    @Test
    void getAuthToken_fallsBackToDockerloginHeader_whenAuthsMapEmpty() throws Exception {
        wireMock.stubFor(WireMock.post(urlMatching(".*/v2/manage/utils/secret.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("X-Swr-Dockerlogin", "docker login -u " + USERNAME + " -p " + PASSWORD + " " + REGISTRY_HOST)
                .withHeader("X-Swr-Expireat", "2026-07-22T10:00:00Z")
                .withBody("{}")));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetAuthToken.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .region(Property.ofValue("eu-west-101"))
            .build();

        var output = task.run(runContext);

        assertThat(output.getUsername(), equalTo(USERNAME));
        assertThat(output.getPassword().getValue(), equalTo(PASSWORD));
        assertThat(output.getRegistry(), equalTo(REGISTRY_HOST));
    }

    @Test
    void getAuthToken_noCredentialsAtAll_throwsActionableError() throws Exception {
        wireMock.stubFor(WireMock.post(urlMatching(".*/v2/manage/utils/secret.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{}")));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetAuthToken.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .region(Property.ofValue("eu-west-101"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("no credentials")));
    }

    @Test
    void getAuthToken_missingAccessKey_throwsActionableError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetAuthToken.builder()
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .region(Property.ofValue("eu-west-101"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("AK/SK")));
    }

    @Test
    void getAuthToken_serviceError_wrapsWithActionableMessage() {
        wireMock.stubFor(WireMock.post(urlMatching(".*/v2/manage/utils/secret.*"))
            .willReturn(aResponse()
                .withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {"error_code": "SVCSTG.SWR.4001", "error_msg": "Access denied."}
                    """)));

        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetAuthToken.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .endpointOverride(Property.ofValue(wireMockUrl()))
            .region(Property.ofValue("eu-west-101"))
            .build();

        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), is(containsString("SWR")));
    }
}
