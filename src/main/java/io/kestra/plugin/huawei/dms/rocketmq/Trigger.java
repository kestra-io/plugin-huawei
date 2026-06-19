package io.kestra.plugin.huawei.dms.rocketmq;

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
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow when new messages arrive on a Huawei DMS for RocketMQ topic.",
    description = """
        Polls the configured topic on a fixed interval and fires one execution per batch when messages are found.
        Messages are stored at `{{ trigger.uri }}` in Kestra internal storage.
        For one-execution-per-message semantics use `RealtimeTrigger`.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: dms_rocketmq_trigger
                namespace: company.team

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Received {{ trigger.messagesCount }} messages from DMS RocketMQ"

                triggers:
                  - id: watch
                    type: io.kestra.plugin.huawei.dms.rocketmq.Trigger
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    nameServerAddr: "dms-instance-id.rocketmq.eu-west-101.myhuaweicloud.com:8100"
                    topic: my-topic
                    groupId: kestra-trigger-group
                    maxRecords: 50
                    interval: PT60S
                """
        )
    }
)
public class Trigger extends AbstractTrigger
    implements PollingTriggerInterface, TriggerOutput<Consume.Output>, DmsRocketMqConnectionInterface {

    @Builder.Default
    @PluginProperty(group = "advanced")
    private Duration interval = Duration.ofSeconds(60);

    @Schema(
        title = "Huawei Cloud access key ID.",
        description = "AK credential for the ACL authentication hook. **Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> accessKeyId;

    @Schema(
        title = "Huawei Cloud secret access key.",
        description = "SK credential for the ACL authentication hook. **Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> secretAccessKey;

    @Schema(
        title = "Name server address.",
        description = "Address of the RocketMQ name server, e.g. `dms-host:8100`. For DMS for RocketMQ, " +
            "copy the name server address from the instance detail page in the Huawei Cloud console."
    )
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> nameServerAddr;

    @Schema(
        title = "DMS instance ID.",
        description = "Huawei Cloud DMS for RocketMQ instance ID. Required when the instance uses instance isolation. " +
            "Leave empty for shared DMS instances."
    )
    @PluginProperty(group = "connection")
    private Property<String> instanceId;

    @Schema(title = "Topic to publish to or consume from.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> topic;

    @Schema(
        title = "Consumer or producer group ID.",
        description = "Consumer group name for Consume/Trigger tasks; producer group name for Publish tasks."
    )
    @PluginProperty(group = "main")
    private Property<String> groupId;

    @Schema(
        title = "Tag filter expression.",
        description = "Server-side filter applied by the broker. Use `*` (default) to receive all tags, " +
            "or a specific tag to filter messages."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<String> tags = Property.ofValue("*");

    @Schema(
        title = "Message body serializer/deserializer.",
        description = "`STRING` (default) or `JSON`."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<RocketMqSerdeType> serdeType = Property.ofValue(RocketMqSerdeType.STRING);

    @Schema(title = "Stop after consuming this many messages per poll cycle.")
    @PluginProperty(group = "execution")
    private Property<Integer> maxRecords;

    @Schema(title = "Stop after this duration elapses per poll cycle.")
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();

        var task = consumeTask();
        var run = task.run(runContext);

        runContext.logger().debug("DMS RocketMQ trigger: {} messages", run.getMessagesCount());

        if (run.getMessagesCount() == 0) {
            return Optional.empty();
        }

        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, run));
    }

    private Consume consumeTask() {
        return Consume.builder()
            .id(this.id)
            .type(Consume.class.getName())
            .accessKeyId(this.accessKeyId)
            .secretAccessKey(this.secretAccessKey)
            .nameServerAddr(this.nameServerAddr)
            .instanceId(this.instanceId)
            .topic(this.topic)
            .groupId(this.groupId)
            .tags(this.tags)
            .serdeType(this.serdeType)
            .maxRecords(this.maxRecords)
            .maxDuration(this.maxDuration)
            .build();
    }
}
