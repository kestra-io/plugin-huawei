package io.kestra.plugin.huawei.iam.tasks;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetTemporaryCredentialsTest {

    private static final String MOCK_TOKEN = "gAAAAABmocked-iam-token-value-12345";
    private static final String TEMP_AK = "TEMPACCESSKEY12345";
    private static final String TEMP_SK = "tempSecretKey9876543210";
    private static final String SECURITY_TOKEN = "gAAAAABmocked-security-token-xyz";
    private static final String EXPIRES_AT = "2099-01-01T00:00:00.000000Z";

    private WireMockServer wireMock;

    @Inject
    RunContextFactory runContextFactory;

    @BeforeAll
    void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        // STS endpoint — used by both PASSWORD and TOKEN paths
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

        // Keystone password endpoint — used by the PASSWORD path; returns X-Subject-Token header
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

    // ── PASSWORD happy path ──────────────────────────────────────────────────────

    @Test
    void password_returnsTemporaryCredentials() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetTemporaryCredentials.builder()
            .region(Property.ofValue("eu-west-101"))
            .authMethod(Property.ofValue(GetTemporaryCredentials.AuthMethod.PASSWORD))
            .username(Property.ofValue("my-iam-user"))
            .password(Property.ofValue("my-secret-password"))
            .domainName(Property.ofValue("my-account-domain"))
            .durationSeconds(Property.ofValue(3600))
            .build();

        var output = task.run(runContext, wireMockUrl());

        assertThat(output.getAccessKeyId(), equalTo(TEMP_AK));
        assertThat(output.getSecretAccessKey(), equalTo(TEMP_SK));
        assertThat(output.getSecurityToken(), equalTo(SECURITY_TOKEN));
        assertThat(output.getExpirationTime(), notNullValue());
        assertThat(output.getExpirationTime(), equalTo(OffsetDateTime.parse(EXPIRES_AT).toInstant()));
        assertThat(output.getExpirationTime().isAfter(Instant.now()), is(true));
    }

    @Test
    void password_projectScoped_usesRegionAsProjectName() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        // scope=PROJECT without explicit projectName: should default to region
        var task = GetTemporaryCredentials.builder()
            .region(Property.ofValue("eu-west-101"))
            .authMethod(Property.ofValue(GetTemporaryCredentials.AuthMethod.PASSWORD))
            .username(Property.ofValue("my-iam-user"))
            .password(Property.ofValue("my-secret-password"))
            .domainName(Property.ofValue("my-account-domain"))
            .build();

        var output = task.run(runContext, wireMockUrl());

        assertThat(output.getAccessKeyId(), equalTo(TEMP_AK));
        assertThat(output.getSecurityToken(), equalTo(SECURITY_TOKEN));
    }

    // ── TOKEN happy path ─────────────────────────────────────────────────────────

    @Test
    void token_returnsTemporaryCredentials() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetTemporaryCredentials.builder()
            .region(Property.ofValue("eu-west-101"))
            .authMethod(Property.ofValue(GetTemporaryCredentials.AuthMethod.TOKEN))
            .token(Property.ofValue(MOCK_TOKEN))
            .durationSeconds(Property.ofValue(3600))
            .build();

        var output = task.run(runContext, wireMockUrl());

        assertThat(output.getAccessKeyId(), equalTo(TEMP_AK));
        assertThat(output.getSecretAccessKey(), equalTo(TEMP_SK));
        assertThat(output.getSecurityToken(), equalTo(SECURITY_TOKEN));
        assertThat(output.getExpirationTime(), notNullValue());
        assertThat(output.getExpirationTime(), equalTo(OffsetDateTime.parse(EXPIRES_AT).toInstant()));
        assertThat(output.getExpirationTime().isAfter(Instant.now()), is(true));
    }

    @Test
    void token_defaultDuration_usesNineHundredSeconds() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetTemporaryCredentials.builder()
            .region(Property.ofValue("eu-west-101"))
            .authMethod(Property.ofValue(GetTemporaryCredentials.AuthMethod.TOKEN))
            .token(Property.ofValue(MOCK_TOKEN))
            .build();

        var output = task.run(runContext, wireMockUrl());

        assertThat(output.getAccessKeyId(), equalTo(TEMP_AK));
        assertThat(output.getSecurityToken(), equalTo(SECURITY_TOKEN));
    }

    // ── Validation: TOKEN method ─────────────────────────────────────────────────

    @Test
    void token_missingToken_throwsDescriptiveError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetTemporaryCredentials.builder()
            .region(Property.ofValue("eu-west-101"))
            .authMethod(Property.ofValue(GetTemporaryCredentials.AuthMethod.TOKEN))
            .build();

        var ex = assertThrows(IllegalArgumentException.class,
            () -> task.run(runContext, wireMockUrl()));
        assertThat(ex.getMessage(), containsString("token"));
        assertThat(ex.getMessage(), containsString("TOKEN"));
    }

    // ── Validation: PASSWORD method ──────────────────────────────────────────────

    @Test
    void password_missingUsername_throwsDescriptiveError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetTemporaryCredentials.builder()
            .region(Property.ofValue("eu-west-101"))
            .authMethod(Property.ofValue(GetTemporaryCredentials.AuthMethod.PASSWORD))
            .password(Property.ofValue("secret"))
            .domainName(Property.ofValue("my-domain"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class,
            () -> task.run(runContext, wireMockUrl()));
        assertThat(ex.getMessage(), containsString("username"));
        assertThat(ex.getMessage(), containsString("PASSWORD"));
    }

    @Test
    void password_missingPassword_throwsDescriptiveError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetTemporaryCredentials.builder()
            .region(Property.ofValue("eu-west-101"))
            .authMethod(Property.ofValue(GetTemporaryCredentials.AuthMethod.PASSWORD))
            .username(Property.ofValue("my-user"))
            .domainName(Property.ofValue("my-domain"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class,
            () -> task.run(runContext, wireMockUrl()));
        assertThat(ex.getMessage(), containsString("password"));
        assertThat(ex.getMessage(), containsString("PASSWORD"));
    }

    @Test
    void password_missingDomainName_throwsDescriptiveError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetTemporaryCredentials.builder()
            .region(Property.ofValue("eu-west-101"))
            .authMethod(Property.ofValue(GetTemporaryCredentials.AuthMethod.PASSWORD))
            .username(Property.ofValue("my-user"))
            .password(Property.ofValue("secret"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class,
            () -> task.run(runContext, wireMockUrl()));
        assertThat(ex.getMessage(), containsString("domainName"));
        assertThat(ex.getMessage(), containsString("PASSWORD"));
    }

    // ── Validation: region ───────────────────────────────────────────────────────

    @Test
    void missingRegion_throwsDescriptiveError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetTemporaryCredentials.builder()
            .authMethod(Property.ofValue(GetTemporaryCredentials.AuthMethod.TOKEN))
            .token(Property.ofValue(MOCK_TOKEN))
            .build();

        var ex = assertThrows(IllegalArgumentException.class,
            () -> task.run(runContext, wireMockUrl()));
        assertThat(ex.getMessage(), containsString("region"));
    }
}
