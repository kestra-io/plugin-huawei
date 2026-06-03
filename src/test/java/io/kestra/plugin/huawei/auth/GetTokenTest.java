package io.kestra.plugin.huawei.auth;

import com.sun.net.httpserver.HttpServer;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class GetTokenTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run_obtainsTokenFromStubbedIamServer() throws Exception {
        // Spin up an in-process IAM stub that mimics the Keystone v3 /v3/auth/tokens contract.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] capturedBody = new String[1];
        final String[] capturedPath = new String[1];
        final String[] capturedContentType = new String[1];

        try {
            server.createContext("/v3/auth/tokens", exchange -> {
                capturedPath[0] = exchange.getRequestURI().getPath();
                capturedContentType[0] = exchange.getRequestHeaders().getFirst("Content-Type");
                capturedBody[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                String responseBody = "{\"token\":{\"expires_at\":\"2026-05-21T10:00:00.000000Z\"}}";
                exchange.getResponseHeaders().add("X-Subject-Token", "MIIabcDEF.token.value");
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();

            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();

            GetToken task = GetToken.builder()
                .iamEndpointOverride(Property.ofValue(endpoint))
                .username(Property.ofValue("alice"))
                .password(Property.ofValue("p4ss"))
                .userDomain(Property.ofValue("acme-corp"))
                .projectName(Property.ofValue("eu-west-101"))
                .build();

            GetToken.Output output = task.run(runContextFactory.of(Collections.emptyMap()));

            assertThat(output.getToken(), notNullValue());
            assertThat(output.getToken().getTokenValue(), notNullValue());
            assertThat(output.getToken().getExpirationTime().toString(), equalTo("2026-05-21T10:00:00Z"));

            assertThat(capturedPath[0], equalTo("/v3/auth/tokens"));
            assertThat(capturedContentType[0], containsString("application/json"));
            assertThat(capturedBody[0], containsString("\"name\":\"alice\""));
            assertThat(capturedBody[0], containsString("\"password\":\"p4ss\""));
            assertThat(capturedBody[0], containsString("\"name\":\"acme-corp\""));
            assertThat(capturedBody[0], containsString("\"project\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void run_surfacesIamErrorBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/v3/auth/tokens", exchange -> {
                String body = "{\"error\":{\"message\":\"The password is wrong.\",\"code\":401}}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();

            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();

            GetToken task = GetToken.builder()
                .iamEndpointOverride(Property.ofValue(endpoint))
                .username(Property.ofValue("alice"))
                .password(Property.ofValue("wrong"))
                .userDomain(Property.ofValue("acme-corp"))
                .projectName(Property.ofValue("eu-west-101"))
                .build();

            RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> task.run(runContextFactory.of(Collections.emptyMap()))
            );
            assertThat(ex.getMessage(), containsString("401"));
            assertThat(ex.getMessage(), containsString("password is wrong"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void run_rejectsAmbiguousScope() throws Exception {
        GetToken task = GetToken.builder()
            .iamEndpointOverride(Property.ofValue("http://127.0.0.1:1"))
            .username(Property.ofValue("alice"))
            .password(Property.ofValue("p4ss"))
            .userDomain(Property.ofValue("acme-corp"))
            .projectName(Property.ofValue("eu-west-101"))
            .domainName(Property.ofValue("acme-corp"))
            .build();

        assertThrows(
            IllegalArgumentException.class,
            () -> task.run(runContextFactory.of(Collections.emptyMap()))
        );
    }

    @Test
    void run_acceptsExpirationOmittedFromBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/v3/auth/tokens", exchange -> {
                String body = "{\"token\":{}}";
                exchange.getResponseHeaders().add("X-Subject-Token", "MIIabcDEF.token.value");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();

            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();

            GetToken task = GetToken.builder()
                .iamEndpointOverride(Property.ofValue(endpoint))
                .username(Property.ofValue("alice"))
                .password(Property.ofValue("p4ss"))
                .userDomain(Property.ofValue("acme-corp"))
                .domainName(Property.ofValue("acme-corp"))
                .build();

            GetToken.Output output = task.run(runContextFactory.of(Collections.emptyMap()));

            assertThat(output.getToken().getTokenValue(), notNullValue());
            assertThat(output.getToken().getExpirationTime(), nullValue());
        } finally {
            server.stop(0);
        }
    }

    @Disabled("Live test — set HUAWEI_USERNAME / HUAWEI_PASSWORD / HUAWEI_DOMAIN_NAME / HUAWEI_REGION env vars and remove @Disabled")
    @Test
    void run_liveHuaweiCloud() throws Exception {
        GetToken task = GetToken.builder()
            .region(Property.ofValue(System.getenv("HUAWEI_REGION")))
            .username(Property.ofValue(System.getenv("HUAWEI_USERNAME")))
            .password(Property.ofValue(System.getenv("HUAWEI_PASSWORD")))
            .userDomain(Property.ofValue(System.getenv("HUAWEI_DOMAIN_NAME")))
            .projectName(Property.ofValue(System.getenv("HUAWEI_REGION")))
            .build();

        GetToken.Output output = task.run(runContextFactory.of(Collections.emptyMap()));

        assertThat(output.getToken().getTokenValue(), notNullValue());
        assertThat(output.getToken().getExpirationTime(), notNullValue());
    }
}
