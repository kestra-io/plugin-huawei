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
import java.util.List;
import java.util.Optional;

@SuperBuilder
@ToString(exclude = {"accessKeyId", "secretAccessKey", "securityToken", "temporaryCredentials"})
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow when a Huawei Cloud CES metric query returns datapoints",
    description = """
        Polls a CES metric on a configurable interval and fires an execution as soon as the query
        returns at least one datapoint. Useful for reacting to metric thresholds without a dedicated
        CES alarm, e.g. to kick off a remediation flow.

        The trigger outputs the same structure as the `Query` task.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger a flow when CPU utilization datapoints are available for an ECS instance.",
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
        )
    }
)
public class Trigger extends AbstractTrigger
    implements PollingTriggerInterface, TriggerOutput<Query.Output>, CesConnectionInterface {

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

        if (output.getCount() == null || output.getCount() == 0) {
            return Optional.empty();
        }

        runContext.logger().debug("CES trigger found {} datapoints", output.getCount());

        var execution = TriggerService.generateExecution(this, conditionContext, triggerContext, output);
        return Optional.of(execution);
    }
}
