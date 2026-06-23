package io.kestra.plugin.huawei.iam;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.huawei.TemporaryCredentialsConfig;
import io.kestra.plugin.huawei.iam.tasks.GetTemporaryCredentials.AuthMethod;
import io.kestra.plugin.huawei.obs.tasks.Upload;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Collections;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies the connection-layer credential exchange path end-to-end:
 * {@code temporaryCredentials} set on any {@code AbstractConnection} subclass →
 * IAM STS exchange via WireMock → {@link io.kestra.plugin.huawei.AbstractConnection.HuaweiClientConfig}
 * carries the exchanged AK/SK + security token.
 *
 * <p>Uses {@link Upload} as a concrete stand-in (since it extends {@code AbstractConnection} through
 * the OBS hierarchy) without performing an actual upload — only {@code huaweiClientConfig} is called.
 */
@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemporaryCredentialsConnectionTest {

    private static final String TEMP_AK = "CONNLEVEL_AK_12345";
    private static final String TEMP_SK = "connLevelSk9876543210";
    private static final String SECURITY_TOKEN = "gAAAAABconn-level-security-token";
    private static final String MOCK_TOKEN = "gAAAAABconn-level-mock-token";
    private static final String EXPIRES_AT = "2099-12-31T23:59:59.000000Z";

    private WireMockServer wireMock;

    @Inject
    RunContextFactory runContextFactory;

    @BeforeAll
    void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        wireMock.stubFor(post(urlPathEqualTo("/v3.0/OS-CREDENTIAL/securitytokens"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json;charset=UTF-8")
                .withBody("""
                    {
                      "credential": {
                        "access": "%s",
                        "secret": "%s",
                        "securitytoken": "%s",
                        "expires_at": "%s"
                      }
                    }
                    """.formatted(TEMP_AK, TEMP_SK, SECURITY_TOKEN, EXPIRES_AT))));

        wireMock.stubFor(post(urlPathEqualTo("/v3/auth/tokens"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json;charset=UTF-8")
                .withHeader("X-Subject-Token", MOCK_TOKEN)
                .withBody("""
                    {
                      "token": {
                        "expires_at": "%s",
                        "methods": ["password"],
                        "project": { "name": "eu-west-101" }
                      }
                    }
                    """.formatted(EXPIRES_AT))));
    }

    @AfterAll
    void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    void temporaryCredentials_password_exchangePopulatesClientConfig() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var tempCreds = TemporaryCredentialsConfig.builder()
            .authMethod(Property.ofValue(AuthMethod.PASSWORD))
            .username(Property.ofValue("my-iam-user"))
            .password(Property.ofValue("my-secret"))
            .domainName(Property.ofValue("my-domain"))
            .durationSeconds(Property.ofValue(3600))
            .build();

        // Use Upload as a concrete AbstractConnection subclass; no actual upload happens here.
        var task = Upload.builder()
            .id("test-upload")
            .type(Upload.class.getName())
            .region(Property.ofValue("eu-west-101"))
            .temporaryCredentials(Property.ofValue(tempCreds))
            .bucket(Property.ofValue("dummy-bucket"))
            .from(Property.ofValue("kestra:///dummy.csv"))
            .key(Property.ofValue("dummy.csv"))
            .build();

        var config = task.huaweiClientConfig(runContext, "http://localhost:" + wireMock.port());

        assertThat(config, notNullValue());
        assertThat(config.accessKeyId(), equalTo(TEMP_AK));
        assertThat(config.secretAccessKey(), equalTo(TEMP_SK));
        assertThat(config.securityToken(), equalTo(SECURITY_TOKEN));
        assertThat(config.region(), equalTo("eu-west-101"));
    }

    @Test
    void temporaryCredentials_token_exchangePopulatesClientConfig() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var tempCreds = TemporaryCredentialsConfig.builder()
            .authMethod(Property.ofValue(AuthMethod.TOKEN))
            .iamToken(Property.ofValue(MOCK_TOKEN))
            .build();

        var task = Upload.builder()
            .id("test-upload")
            .type(Upload.class.getName())
            .region(Property.ofValue("eu-west-101"))
            .temporaryCredentials(Property.ofValue(tempCreds))
            .bucket(Property.ofValue("dummy-bucket"))
            .from(Property.ofValue("kestra:///dummy.csv"))
            .key(Property.ofValue("dummy.csv"))
            .build();

        var config = task.huaweiClientConfig(runContext, "http://localhost:" + wireMock.port());

        assertThat(config.accessKeyId(), equalTo(TEMP_AK));
        assertThat(config.secretAccessKey(), equalTo(TEMP_SK));
        assertThat(config.securityToken(), equalTo(SECURITY_TOKEN));
    }

    @Test
    void withoutTemporaryCredentials_usesStaticAkSk() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Upload.builder()
            .id("test-upload")
            .type(Upload.class.getName())
            .region(Property.ofValue("eu-west-101"))
            .accessKeyId(Property.ofValue("STATIC_AK"))
            .secretAccessKey(Property.ofValue("STATIC_SK"))
            .bucket(Property.ofValue("dummy-bucket"))
            .from(Property.ofValue("kestra:///dummy.csv"))
            .key(Property.ofValue("dummy.csv"))
            .build();

        // No temporaryCredentials — static AK/SK path; iamEndpointOverride is unused
        var config = task.huaweiClientConfig(runContext, null);

        assertThat(config.accessKeyId(), equalTo("STATIC_AK"));
        assertThat(config.secretAccessKey(), equalTo("STATIC_SK"));
        assertThat(config.securityToken(), nullValue());
    }

    @Test
    void huaweiClientConfig_toString_redactsSecrets() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = Upload.builder()
            .id("test-upload")
            .type(Upload.class.getName())
            .region(Property.ofValue("eu-west-101"))
            .accessKeyId(Property.ofValue("SHOULD_BE_REDACTED"))
            .secretAccessKey(Property.ofValue("ALSO_REDACTED"))
            .bucket(Property.ofValue("dummy-bucket"))
            .from(Property.ofValue("kestra:///dummy.csv"))
            .key(Property.ofValue("dummy.csv"))
            .build();

        var config = task.huaweiClientConfig(runContext, null);
        var str = config.toString();

        assertThat(str.contains("SHOULD_BE_REDACTED"), is(false));
        assertThat(str.contains("ALSO_REDACTED"), is(false));
        assertThat(str.contains("****"), is(true));
    }
}
