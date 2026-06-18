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
    private static final String EXPIRES_AT = "2026-06-19T10:00:00.000000Z";

    private WireMockServer wireMock;

    @Inject
    RunContextFactory runContextFactory;

    @BeforeAll
    void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        wireMock.stubFor(post(urlPathEqualTo("/v3/auth/tokens"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json;charset=UTF-8")
                .withHeader("X-Subject-Token", MOCK_TOKEN)
                .withBody("""
                    {
                      "token": {
                        "expires_at": "%s",
                        "issued_at": "2026-06-18T10:00:00.000000Z",
                        "methods": ["hw_sdk_aksk"],
                        "catalog": [],
                        "roles": []
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
    void run_projectScope_returnsToken() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetToken.builder()
            .accessKeyId(Property.ofValue("test-ak"))
            .secretAccessKey(Property.ofValue("test-sk"))
            .region(Property.ofValue("eu-west-101"))
            .projectId(Property.ofValue("test-project-id"))
            .scope(Property.ofValue(GetToken.TokenScope.PROJECT))
            .build();

        var output = task.run(runContext, wireMockUrl());

        assertThat(output.getTokenValue(), equalTo(MOCK_TOKEN));
        assertThat(output.getExpirationTime(), notNullValue());
        assertThat(output.getExpirationTime().isAfter(Instant.now()), is(true));
        assertThat(output.getExpirationTime(), equalTo(OffsetDateTime.parse(EXPIRES_AT).toInstant()));
    }

    @Test
    void run_domainScope_returnsToken() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetToken.builder()
            .accessKeyId(Property.ofValue("test-ak"))
            .secretAccessKey(Property.ofValue("test-sk"))
            .region(Property.ofValue("eu-west-101"))
            .domainId(Property.ofValue("test-domain-id"))
            .scope(Property.ofValue(GetToken.TokenScope.DOMAIN))
            .build();

        var output = task.run(runContext, wireMockUrl());

        assertThat(output.getTokenValue(), equalTo(MOCK_TOKEN));
        assertThat(output.getExpirationTime().isAfter(Instant.now()), is(true));
    }

    @Test
    void run_missingProjectId_throwsDescriptiveError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetToken.builder()
            .accessKeyId(Property.ofValue("test-ak"))
            .secretAccessKey(Property.ofValue("test-sk"))
            .region(Property.ofValue("eu-west-101"))
            .scope(Property.ofValue(GetToken.TokenScope.PROJECT))
            .build();

        var ex = assertThrows(IllegalArgumentException.class,
            () -> task.run(runContext, wireMockUrl()));
        assertThat(ex.getMessage(), containsString("projectId"));
        assertThat(ex.getMessage(), containsString("PROJECT"));
    }

    @Test
    void run_missingDomainId_throwsDescriptiveError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetToken.builder()
            .accessKeyId(Property.ofValue("test-ak"))
            .secretAccessKey(Property.ofValue("test-sk"))
            .region(Property.ofValue("eu-west-101"))
            .scope(Property.ofValue(GetToken.TokenScope.DOMAIN))
            .build();

        var ex = assertThrows(IllegalArgumentException.class,
            () -> task.run(runContext, wireMockUrl()));
        assertThat(ex.getMessage(), containsString("domainId"));
        assertThat(ex.getMessage(), containsString("DOMAIN"));
    }

    @Test
    void run_missingCredentials_throwsDescriptiveError() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = GetToken.builder()
            .region(Property.ofValue("eu-west-101"))
            .projectId(Property.ofValue("test-project-id"))
            .scope(Property.ofValue(GetToken.TokenScope.PROJECT))
            .build();

        var ex = assertThrows(IllegalArgumentException.class,
            () -> task.run(runContext, wireMockUrl()));
        assertThat(ex.getMessage(), containsString("accessKeyId"));
        assertThat(ex.getMessage(), containsString("secretAccessKey"));
    }
}
