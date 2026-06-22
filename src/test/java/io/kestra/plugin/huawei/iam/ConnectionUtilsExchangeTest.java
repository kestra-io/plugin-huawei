package io.kestra.plugin.huawei.iam;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.huawei.ConnectionUtils;
import io.kestra.plugin.huawei.TemporaryCredentialsConfig;
import io.kestra.plugin.huawei.iam.tasks.GetTemporaryCredentials.AuthMethod;
import io.kestra.plugin.huawei.iam.tasks.GetTemporaryCredentials.TokenScope;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the extracted {@link ConnectionUtils#exchangeForTemporaryCredentials} directly, using
 * the same WireMock stubs as the task-level tests.
 */
@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectionUtilsExchangeTest {

    private static final String MOCK_TOKEN = "gAAAAABmocked-iam-token-exchange-test";
    private static final String TEMP_AK = "EXCHANGETEMPACCESSKEY";
    private static final String TEMP_SK = "exchangeTempSecretKey9876";
    private static final String SECURITY_TOKEN = "gAAAAABmocked-security-token-exchange";
    private static final String EXPIRES_AT = "2099-06-01T00:00:00.000000Z";

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

    private String wireMockUrl() {
        return "http://localhost:" + wireMock.port();
    }

    @Test
    void exchange_passwordMethod_returnsCredentials() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var config = TemporaryCredentialsConfig.builder()
            .authMethod(Property.ofValue(AuthMethod.PASSWORD))
            .username(Property.ofValue("my-iam-user"))
            .password(Property.ofValue("my-secret-password"))
            .domainName(Property.ofValue("my-account-domain"))
            .durationSeconds(Property.ofValue(3600))
            .build();

        var result = ConnectionUtils.exchangeForTemporaryCredentials(
            runContext, config, "eu-west-101", wireMockUrl());

        assertThat(result.accessKeyId(), equalTo(TEMP_AK));
        assertThat(result.secretAccessKey(), equalTo(TEMP_SK));
        assertThat(result.securityToken(), equalTo(SECURITY_TOKEN));
        assertThat(result.expiresAt(), notNullValue());
        assertThat(result.expiresAt(), equalTo(OffsetDateTime.parse(EXPIRES_AT).toInstant()));
        assertThat(result.expiresAt().isAfter(Instant.now()), is(true));
    }

    @Test
    void exchange_tokenMethod_returnsCredentials() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var config = TemporaryCredentialsConfig.builder()
            .authMethod(Property.ofValue(AuthMethod.TOKEN))
            .iamToken(Property.ofValue(MOCK_TOKEN))
            .durationSeconds(Property.ofValue(1800))
            .build();

        var result = ConnectionUtils.exchangeForTemporaryCredentials(
            runContext, config, "eu-west-101", wireMockUrl());

        assertThat(result.accessKeyId(), equalTo(TEMP_AK));
        assertThat(result.secretAccessKey(), equalTo(TEMP_SK));
        assertThat(result.securityToken(), equalTo(SECURITY_TOKEN));
        assertThat(result.expiresAt(), notNullValue());
    }

    @Test
    void exchange_passwordMethod_withExplicitProjectName_succeeds() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var config = TemporaryCredentialsConfig.builder()
            .authMethod(Property.ofValue(AuthMethod.PASSWORD))
            .username(Property.ofValue("my-iam-user"))
            .password(Property.ofValue("my-secret-password"))
            .domainName(Property.ofValue("my-account-domain"))
            .scope(Property.ofValue(TokenScope.PROJECT))
            .projectName(Property.ofValue("eu-west-101"))
            .build();

        var result = ConnectionUtils.exchangeForTemporaryCredentials(
            runContext, config, "eu-west-101", wireMockUrl());

        assertThat(result.accessKeyId(), equalTo(TEMP_AK));
    }

    @Test
    void exchange_tokenMethod_missingIamToken_throwsDescriptiveError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var config = TemporaryCredentialsConfig.builder()
            .authMethod(Property.ofValue(AuthMethod.TOKEN))
            .build();

        var ex = assertThrows(IllegalArgumentException.class,
            () -> ConnectionUtils.exchangeForTemporaryCredentials(
                runContext, config, "eu-west-101", wireMockUrl()));
        assertThat(ex.getMessage().toLowerCase().contains("token"), is(true));
    }

    @Test
    void exchange_redactsSecretsInToString() throws Exception {
        var result = new ConnectionUtils.TemporaryCredentials("AK123", "SK456", "ST789", Instant.now());
        var str = result.toString();
        // Secrets must be redacted
        assertThat(str.contains("AK123"), is(false));
        assertThat(str.contains("SK456"), is(false));
        assertThat(str.contains("ST789"), is(false));
        assertThat(str.contains("****"), is(true));
    }
}
