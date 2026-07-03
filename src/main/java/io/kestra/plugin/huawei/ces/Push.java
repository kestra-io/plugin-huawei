package io.kestra.plugin.huawei.ces;

import com.huaweicloud.sdk.ces.v1.CesClient;
import com.huaweicloud.sdk.ces.v1.model.CreateMetricDataRequest;
import com.huaweicloud.sdk.ces.v1.model.CreateMetricDataRequestBody;
import com.huaweicloud.sdk.ces.v1.model.MetricInfo;
import com.huaweicloud.sdk.ces.v1.model.MetricsDimension;
import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
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
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Push custom metrics to Huawei Cloud CES (Cloud Eye Service)",
    description = """
        Reports one or more datapoints for custom metrics under a single namespace. CES limits a single
        request to 10 metric datapoints — larger batches are chunked automatically.

        Custom namespaces must follow the `service.item` format (e.g. `MyApp.Custom`) and must NOT use
        the `SYS.` prefix, which is reserved for Huawei Cloud system namespaces.

        Authentication uses AK/SK request signing. Provide `accessKeyId` and `secretAccessKey` via
        `{{ secret('NAME') }}`, or configure `temporaryCredentials` for inline IAM credential exchange.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Push a custom queue-depth metric to CES.",
            full = true,
            code = """
                id: ces_push
                namespace: company.team

                tasks:
                  - id: push
                    type: io.kestra.plugin.huawei.ces.Push
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    namespace: MyApp.Custom
                    metrics:
                      - metricName: queue_depth
                        value: 42
                        unit: Count
                        dimensions:
                          - name: queue_name
                            value: orders
                """
        )
    },
    metrics = {
        @Metric(name = "ces.push.count", type = Counter.TYPE, unit = "datapoints",
            description = "Number of metric datapoints successfully pushed to CES.")
    }
)
public class Push extends AbstractCes implements RunnableTask<Push.Output> {

    // CES caps a single createMetricData request at 10 datapoints.
    private static final int MAX_BATCH_SIZE = 10;

    // CES marks `ttl` (data validity period, 1–604800s) as mandatory; default to 2 days when unset.
    private static final int DEFAULT_TTL_SECONDS = 172800;

    @Schema(
        title = "Metric namespace",
        description = """
            The `service.item` namespace to publish metrics under, e.g. `MyApp.Custom`. Must NOT start
            with `SYS.`, which is reserved for Huawei Cloud system namespaces.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> namespace;

    @Schema(title = "Metric datapoints to push")
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<MetricValue>> metrics;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rNamespace = runContext.render(namespace).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("namespace is required"));
        CesUtils.validateCustomNamespace(rNamespace);

        var rMetrics = runContext.render(metrics).asList(MetricValue.class);
        if (rMetrics.isEmpty()) {
            throw new IllegalArgumentException("metrics must contain at least one datapoint");
        }

        var client = client(runContext);

        var bodies = rMetrics.stream()
            .map(m -> toRequestBody(runContext, rNamespace, m))
            .toList();

        var count = 0;
        for (var chunk : partition(bodies, MAX_BATCH_SIZE)) {
            pushChunk(client, chunk);
            count += chunk.size();
        }

        runContext.metric(Counter.of("ces.push.count", count));
        runContext.logger().info("Pushed {} datapoints to CES namespace '{}'", count, rNamespace);

        return Output.builder().count(count).build();
    }

    private CreateMetricDataRequestBody toRequestBody(RunContext runContext, String namespace, MetricValue metric) {
        try {
            var rMetricName = runContext.render(metric.getMetricName()).as(String.class)
                .orElseThrow(() -> new IllegalArgumentException("metrics[].metricName is required"));
            var rDimensions = runContext.render(metric.getDimensions()).asList(Dimension.class);
            if (rDimensions.size() > 3) {
                throw new IllegalArgumentException(
                    "CES supports at most 3 dimensions per metric, but " + rDimensions.size() + " were provided for metric '" + rMetricName + "'.");
            }
            var rValue = runContext.render(metric.getValue()).as(Double.class)
                .orElseThrow(() -> new IllegalArgumentException("metrics[].value is required for metric '" + rMetricName + "'"));
            var rUnit = runContext.render(metric.getUnit()).as(String.class).orElse(null);
            var rType = runContext.render(metric.getType()).as(String.class).orElse("float");
            // CES requires both collect_time and ttl on every datapoint. Default collect_time to now
            // (which sits inside CES's accepted [now-3d, now+10min] window) and ttl to DEFAULT_TTL_SECONDS.
            var rCollectTime = runContext.render(metric.getCollectTime()).as(Long.class).orElse(System.currentTimeMillis());
            var rTtl = runContext.render(metric.getTtl()).as(Integer.class).orElse(DEFAULT_TTL_SECONDS);

            var sdkDimensions = new ArrayList<MetricsDimension>(rDimensions.size());
            for (var d : rDimensions) {
                sdkDimensions.add(new MetricsDimension()
                    .withName(runContext.render(d.getName()).as(String.class).orElseThrow())
                    .withValue(runContext.render(d.getValue()).as(String.class).orElseThrow()));
            }

            var metricInfo = new MetricInfo()
                .withNamespace(namespace)
                .withMetricName(rMetricName)
                .withDimensions(sdkDimensions);

            var body = new CreateMetricDataRequestBody()
                .withMetric(metricInfo)
                .withValue(rValue)
                .withUnit(rUnit)
                .withType(rType)
                .withCollectTime(rCollectTime)
                .withTtl(rTtl);
            return body;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid metric in 'metrics': " + e.getMessage(), e);
        }
    }

    private static void pushChunk(CesClient client, List<CreateMetricDataRequestBody> chunk) {
        try {
            client.createMetricData(new CreateMetricDataRequest().withBody(chunk));
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "CES push failed (HTTP " + e.getHttpStatusCode() + "): " + e.getErrorMsg() +
                " — verify the namespace format and that the AK/SK has 'CES FullAccess' permission.", e);
        } catch (SdkException e) {
            throw new IllegalStateException("CES SDK error pushing metrics: " + e.getMessage(), e);
        }
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        return IntStream.range(0, (items.size() + size - 1) / size)
            .mapToObj(i -> items.subList(i * size, Math.min(items.size(), (i + 1) * size)))
            .toList();
    }

    @Value
    @Builder
    @Jacksonized
    public static class MetricValue {

        @Schema(title = "Metric name", description = "E.g. `queue_depth`.")
        @NotNull
        @PluginProperty(group = "main")
        Property<String> metricName;

        @Schema(
            title = "Dimensions identifying the monitored resource",
            description = "Up to 3 name/value pairs."
        )
        @PluginProperty(group = "main")
        Property<List<Dimension>> dimensions;

        @Schema(title = "Datapoint value")
        @NotNull
        @PluginProperty(group = "main")
        Property<Double> value;

        @Schema(title = "Unit of the value", description = "E.g. `%`, `Count`, `Bytes`. Optional.")
        @PluginProperty(group = "advanced")
        Property<String> unit;

        @Schema(
            title = "Value type",
            description = "Data type reported to CES. Defaults to `float`, which covers virtually all use cases."
        )
        @Builder.Default
        @PluginProperty(group = "advanced")
        Property<String> type = Property.ofValue("float");

        @Schema(
            title = "Collection time (epoch milliseconds)",
            description = "When the datapoint was collected. CES requires this field and accepts values within roughly [now - 3 days, now + 10 minutes]; defaults to the current time when omitted."
        )
        @PluginProperty(group = "advanced")
        Property<Long> collectTime;

        @Schema(
            title = "Time-to-live (data validity period) in seconds",
            description = "How long CES treats this datapoint as valid, 1–604800s. CES requires this field; defaults to 172800 (2 days) when omitted."
        )
        @PluginProperty(group = "advanced")
        Property<Integer> ttl;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Number of datapoints successfully pushed to CES")
        private final Integer count;
    }
}
