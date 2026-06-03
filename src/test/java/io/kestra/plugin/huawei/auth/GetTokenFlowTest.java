package io.kestra.plugin.huawei.auth;

import com.sun.net.httpserver.HttpServer;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.junit.annotations.LoadFlows;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;
import io.kestra.core.queues.QueueException;
import io.kestra.core.runners.TestRunnerUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@KestraTest(startRunner = true)
class GetTokenFlowTest {

    private static final String TENANT_ID = "huawei-auth";

    @Inject
    private TestRunnerUtils runnerUtils;

    @Test
    @LoadFlows(value = { "flows/auth-get-token.yaml" }, tenantId = TENANT_ID)
    void getTokenFlow_succeedsAgainstStubbedIam() throws TimeoutException, QueueException, Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/v3/auth/tokens", exchange -> {
                String responseBody = "{\"token\":{\"expires_at\":\"2026-05-21T10:00:00.000000Z\"}}";
                exchange.getResponseHeaders().add("X-Subject-Token", "flow-test-token-value");
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();

            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();

            Execution execution = runnerUtils.runOne(
                TENANT_ID,
                "io.kestra.tests.huawei",
                "auth-get-token",
                null,
                (f, e) -> Map.of("endpoint", endpoint),
                Duration.ofSeconds(60)
            );

            assertThat(execution, notNullValue());
            assertThat(execution.getState().getCurrent(), equalTo(State.Type.SUCCESS));
            assertThat(execution.getTaskRunList(), notNullValue());
            assertThat(execution.findTaskRunsByTaskId("get_token").size(), equalTo(1));
            assertThat(
                execution.findTaskRunsByTaskId("get_token").getFirst().getState().getCurrent(),
                equalTo(State.Type.SUCCESS)
            );
        } finally {
            server.stop(0);
        }
    }
}
