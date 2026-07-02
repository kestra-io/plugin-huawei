package io.kestra.plugin.huawei.ces;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.huawei.TemporaryCredentialsConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@SuperBuilder
@ToString(exclude = {"accessKeyId", "secretAccessKey", "securityToken", "temporaryCredentials"})
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow when a Huawei Cloud CES metric query returns datapoints matching a threshold",
    description = """
        Polls a CES metric on a configurable interval and fires an execution as soon as the query
        returns at least one new datapoint. Useful for reacting to metric thresholds without a
        dedicated CES alarm, e.g. to kick off a remediation flow.

        A watermark (the timestamp of the most recent datapoint seen) is persisted in the flow's
        namespace [KV Store](https://kestra.io/docs/concepts/kv-store) between polls, so datapoints
        already seen in a previous overlapping `window` never fire the trigger twice.

        The trigger outputs the same structure as the `Query` task, containing only the new datapoints
        that fired this execution.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger a flow whenever new CPU utilization datapoints are available for an ECS instance.",
            full = true,
            code = """
                id: ces_trigger
                namespace: company.team

                tasks:
                  - id: process
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.series | length }} new datapoints, latest={{ trigger.series | last }}"

                triggers:
                  - id: watch
                    type: io.kestra.plugin.huawei.ces.Trigger
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    namespace: SYS.ECS
                    metricName: cpu_util
                    dimensions:
                      - name: instance_id
                        value: "abc123def456"
                    statistic: AVERAGE
                    period: FIVE_MINUTES
                    window: PT10M
                    interval: PT5M
                """
        ),
        @Example(
            title = "Trigger a flow only when average CPU utilization exceeds 80%.",
            full = true,
            code = """
                id: ces_trigger_threshold
                namespace: company.team

                tasks:
                  - id: alert
                    type: io.kestra.plugin.core.log.Log
                    message: "High CPU usage detected: {{ trigger.series | last }}"

                triggers:
                  - id: watch_high_cpu
                    type: io.kestra.plugin.huawei.ces.Trigger
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    namespace: SYS.ECS
                    metricName: cpu_util
                    dimensions:
                      - name: instance_id
                        value: "abc123def456"
                    statistic: AVERAGE
                    period: FIVE_MINUTES
                    window: PT10M
                    interval: PT5M
                    threshold: 80
                    comparisonOperator: GREATER_THAN
                """
        )
    }
)
public class Trigger extends AbstractTrigger
    implements PollingTriggerInterface, TriggerOutput<Query.Output>, CesConnectionInterface {

    private static final String WATERMARK_DESCRIPTION =
        "CES Trigger watermark — timestamp (epoch milliseconds) of the most recent datapoint already fired, used to avoid re-firing on overlapping poll windows.";

    private Property<String> accessKeyId;
    private Property<String> secretAccessKey;
    private Property<String> securityToken;
    private Property<String> projectId;
    private Property<String> domainId;
    private Property<String> region;
    private Property<TemporaryCredentialsConfig> temporaryCredentials;
    private Property<String> endpointOverride;
    private Property<String> endpointSuffix;

    @Schema(title = "Metric namespace")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> namespace;

    @Schema(title = "Metric name")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> metricName;

    @Schema(
        title = "Dimensions identifying the monitored resource",
        description = "1 to 3 name/value pairs. CES requires at least one dimension to identify the exact resource instance."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<Dimension>> dimensions;

    @Schema(title = "Statistic to aggregate datapoints with")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Query.Statistic> statistic = Property.ofValue(Query.Statistic.AVERAGE);

    @Schema(title = "Aggregation period")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Query.Period> period = Property.ofValue(Query.Period.FIVE_MINUTES);

    @Schema(title = "Time window to query, ending now")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Duration> window = Property.ofValue(Duration.ofHours(1));

    @Schema(
        title = "Threshold to compare datapoint values against",
        description = """
            When set, the trigger only fires if at least one new datapoint's value satisfies
            `value <comparisonOperator> threshold`. When omitted (default), the trigger fires as soon
            as any new datapoint is found, regardless of its value.
            """
    )
    @PluginProperty(group = "main")
    private Property<Double> threshold;

    @Schema(
        title = "Comparison operator applied to `threshold`",
        description = "Ignored when `threshold` is not set. Defaults to `GREATER_THAN`."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<ComparisonOperator> comparisonOperator = Property.ofValue(ComparisonOperator.GREATER_THAN);

    @Schema(
        title = "Polling interval",
        description = "How often the trigger queries CES, as an ISO-8601 duration (e.g. `PT5M`). Defaults to 5 minutes."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Duration interval = Duration.ofMinutes(5);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext triggerContext) throws Exception {
        var runContext = conditionContext.getRunContext();

        var task = Query.builder()
            .id(this.getId())
            .type(Query.class.getName())
            .accessKeyId(accessKeyId)
            .secretAccessKey(secretAccessKey)
            .securityToken(securityToken)
            .projectId(projectId)
            .domainId(domainId)
            .region(region)
            .temporaryCredentials(temporaryCredentials)
            .endpointOverride(endpointOverride)
            .endpointSuffix(endpointSuffix)
            .namespace(namespace)
            .metricName(metricName)
            .dimensions(dimensions)
            .statistic(statistic)
            .period(period)
            .window(window)
            .build();

        var output = task.run(runContext);
        if (output.getSeries() == null || output.getSeries().isEmpty()) {
            return Optional.empty();
        }

        var kv = runContext.namespaceKv(triggerContext.getNamespace());
        var watermarkKey = watermarkKey(triggerContext);
        var lastWatermark = readWatermark(kv, watermarkKey);

        var newPoints = output.getSeries().stream()
            .filter(p -> p.getTimestamp() != null && (lastWatermark == null || p.getTimestamp().isAfter(lastWatermark)))
            .toList();

        // Advance the watermark to the latest datapoint seen this poll — even if nothing fires below —
        // so a future poll never re-inspects it.
        var maxTimestamp = output.getSeries().stream()
            .map(Query.Point::getTimestamp)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder());
        if (maxTimestamp.isPresent() && (lastWatermark == null || maxTimestamp.get().isAfter(lastWatermark))) {
            writeWatermark(kv, watermarkKey, maxTimestamp.get());
        }

        if (newPoints.isEmpty()) {
            return Optional.empty();
        }

        var rThreshold = runContext.render(threshold).as(Double.class).orElse(null);
        var matching = newPoints;
        if (rThreshold != null) {
            var rOperator = runContext.render(comparisonOperator).as(ComparisonOperator.class).orElse(ComparisonOperator.GREATER_THAN);
            matching = newPoints.stream()
                .filter(p -> p.getValue() != null && rOperator.test(p.getValue(), rThreshold))
                .toList();
        }

        if (matching.isEmpty()) {
            return Optional.empty();
        }

        runContext.logger().debug("CES trigger found {} new datapoints ({} matching threshold)", newPoints.size(), matching.size());

        var triggerOutput = Query.Output.builder()
            .count(matching.size())
            .series(matching)
            .build();

        var execution = TriggerService.generateExecution(this, conditionContext, triggerContext, triggerOutput);
        return Optional.of(execution);
    }

    private String watermarkKey(TriggerContext triggerContext) {
        return "ces_trigger_watermark_" + triggerContext.getFlowId() + "_" + triggerContext.getTriggerId();
    }

    private static Instant readWatermark(KVStore kv, String key) throws Exception {
        return kv.getValue(key)
            .map(KVValue::value)
            .map(v -> Instant.ofEpochMilli(((Number) v).longValue()))
            .orElse(null);
    }

    private static void writeWatermark(KVStore kv, String key, Instant value) throws Exception {
        kv.put(key, new KVValueAndMetadata(new KVMetadata(WATERMARK_DESCRIPTION, (Duration) null), value.toEpochMilli()), true);
    }

    public enum ComparisonOperator {
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL,
        EQUAL;

        boolean test(double value, double threshold) {
            return switch (this) {
                case GREATER_THAN -> value > threshold;
                case GREATER_THAN_OR_EQUAL -> value >= threshold;
                case LESS_THAN -> value < threshold;
                case LESS_THAN_OR_EQUAL -> value <= threshold;
                case EQUAL -> value == threshold;
            };
        }
    }
}
