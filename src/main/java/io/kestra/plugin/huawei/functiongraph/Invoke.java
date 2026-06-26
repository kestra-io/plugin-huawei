package io.kestra.plugin.huawei.functiongraph;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.functiongraph.v2.model.InvokeFunctionRequest;
import com.huaweicloud.sdk.functiongraph.v2.model.InvokeFunctionResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.executions.metrics.Timer;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Synchronously invoke a Huawei Cloud FunctionGraph function.",
    description = """
        Invokes a FunctionGraph function synchronously and captures its response. The function output
        is stored in Kestra internal storage and accessible via `{{ outputs.<taskId>.uri }}`.

        Authentication uses AK/SK request signing. Provide `accessKeyId` and `secretAccessKey` via
        `{{ secret('NAME') }}`, or configure `temporaryCredentials` for inline IAM credential exchange.

        If the function runtime reports an error (a non-2xx function execution status),
        the task throws `FunctionGraphInvokeException` with a message pointing to the LTS logs.
        HTTP-level failures (4xx/5xx) are also surfaced as `FunctionGraphInvokeException`.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Invoke a FunctionGraph function with a payload.",
            full = true,
            code = """
                id: functiongraph_invoke
                namespace: company.team

                tasks:
                  - id: invoke
                    type: io.kestra.plugin.huawei.functiongraph.Invoke
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    functionUrn: "urn:fss:eu-west-101:abc123:function:default:my-fn:latest"
                    functionPayload:
                      key: value
                      date: "2024-01-01"
                """
        ),
        @Example(
            title = "Invoke a function using the European sovereign cloud endpoint suffix.",
            full = true,
            code = """
                id: functiongraph_invoke_eu
                namespace: company.team

                tasks:
                  - id: invoke
                    type: io.kestra.plugin.huawei.functiongraph.Invoke
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    endpointSuffix: myhuaweicloud.eu
                    functionUrn: "urn:fss:eu-west-101:abc123:function:default:my-fn:latest"
                """
        )
    }
)
@Metric(name = "file.size", type = "counter", unit = "bytes", description = "Size of the function response stored in internal storage.")
@Metric(name = "duration", type = "timer", description = "Wall-clock time of the FunctionGraph invocation.")
public class Invoke extends AbstractFunctionGraph implements RunnableTask<Invoke.Output> {

    @Schema(
        title = "Full URN of the function to invoke.",
        description = """
            The function URN uniquely identifies the function and version to invoke. Format:
            `urn:fss:<region>:<project_id>:function:<pkg>:<name>:<qualifier>`

            Example: `urn:fss:eu-west-101:abc123:function:default:my-fn:latest`

            Find the URN in the FunctionGraph console under the function's **Configuration** tab.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> functionUrn;

    @Schema(
        title = "Input payload passed to the function.",
        description = """
            JSON-serializable map sent as the event body to the function. The function receives it as
            its `event` parameter. When omitted, an empty body is sent.
            """
    )
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> functionPayload;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rUrn = runContext.render(functionUrn).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("functionUrn is required"));
        var rPayload = runContext.render(functionPayload).asMap(String.class, Object.class);

        var client = client(runContext);

        var request = new InvokeFunctionRequest()
            .withFunctionUrn(rUrn)
            .withXCFFRequestVersion("v1")
            .withBody(rPayload);

        runContext.logger().debug("Invoking FunctionGraph function '{}'", rUrn);

        var start = System.nanoTime();

        InvokeFunctionResponse response;
        try {
            response = client.invokeFunction(request);
        } catch (ServiceResponseException e) {
            throw new FunctionGraphInvokeException(
                "FunctionGraph invocation failed (HTTP " + e.getHttpStatusCode() + ") for function '" + rUrn +
                "': " + e.getErrorMsg() + " — verify the function URN is correct and the AK/SK has 'FunctionGraph InvokeFunction' permission.",
                e
            );
        } catch (SdkException e) {
            throw new FunctionGraphInvokeException(
                "FunctionGraph SDK error invoking function '" + rUrn + "': " + e.getMessage(), e);
        }

        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        // `status` is the function's execution HTTP status (200 on success), NOT a 0=success flag.
        // A non-2xx value means the function runtime reported an error (e.g. an uncaught exception).
        var status = response.getStatus();
        if (status != null && (status < 200 || status >= 300)) {
            var errorBody = response.getResult() != null ? response.getResult() : "(no output)";
            throw new FunctionGraphInvokeException(
                "FunctionGraph function '" + rUrn + "' returned a function-level error (status " + status + "): " +
                errorBody + ". Check the function logs in LTS."
            );
        }

        var resultString = response.getResult() != null ? response.getResult() : "";
        var resultBytes = resultString.getBytes(StandardCharsets.UTF_8);

        var uri = runContext.storage().putFile(new ByteArrayInputStream(resultBytes), "result.json");

        runContext.metric(Counter.of("file.size", resultBytes.length));
        runContext.metric(Timer.of("duration", elapsed));

        runContext.logger().info("FunctionGraph function '{}' invoked successfully, requestId={}, size={} bytes",
            rUrn, response.getXCffRequestId(), resultBytes.length);

        return Output.builder()
            .uri(uri)
            .contentLength((long) resultBytes.length)
            .requestId(response.getXCffRequestId())
            .statusCode(response.getHttpStatusCode())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "URI of the function response stored in Kestra internal storage.")
        private final URI uri;

        @Schema(title = "Size of the function response in bytes.")
        private final Long contentLength;

        @Schema(title = "FunctionGraph request ID (`X-Cff-Request-Id` header value).")
        private final String requestId;

        @Schema(title = "HTTP status code returned by the FunctionGraph invocation API.")
        private final Integer statusCode;
    }
}
