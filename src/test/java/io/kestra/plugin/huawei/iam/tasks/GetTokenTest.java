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
class GetTokenTest {

    private static final String MOCK_TOKEN = "gAAAAABmocked-iam-token-value-12345";
    private static final String TEMP_AK = "TEMPACCESSKEY12345";
    private static final String TEMP_SK = "tempSecretKey9876543210";
    private static final String SECURITY_TOKEN = "gAAAAABmocked-security-token-xyz";
    private static final String EXPIRES_AT = "2026-06-19T10:00:00.000000Z";

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
    void run_returnsTemporaryCredentials() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetToken.builder()
            .region(Property.ofValue("eu-west-101"))
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
    void run_defaultDuration_usesNineHundredSeconds() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetToken.builder()
            .region(Property.ofValue("eu-west-101"))
            .token(Property.ofValue(MOCK_TOKEN))
            .build();

        var output = task.run(runContext, wireMockUrl());

        assertThat(output.getAccessKeyId(), equalTo(TEMP_AK));
        assertThat(output.getSecurityToken(), equalTo(SECURITY_TOKEN));
    }

    @Test
    void run_missingToken_throwsDescriptiveError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetToken.builder()
            .region(Property.ofValue("eu-west-101"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class,
            () -> task.run(runContext, wireMockUrl()));
        assertThat(ex.getMessage(), containsString("token"));
    }

    @Test
    void run_missingRegion_throwsDescriptiveError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetToken.builder()
            .token(Property.ofValue(MOCK_TOKEN))
            .build();

        var ex = assertThrows(IllegalArgumentException.class,
            () -> task.run(runContext, wireMockUrl()));
        assertThat(ex.getMessage(), containsString("region"));
    }
}
