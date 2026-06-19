package io.kestra.plugin.huawei.dms.kafka;

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
import jakarta.validation.constraints.AssertTrue;
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
    title = "Trigger a flow when new messages arrive on a Huawei DMS for Kafka topic.",
    description = """
        Polls the configured topic on a fixed interval and fires one execution per batch when messages are found.
        Messages are stored at `{{ trigger.uri }}` in Kestra internal storage; `{{ trigger.messagesCount }}` gives the batch size.
        For one-execution-per-message semantics use `RealtimeTrigger`.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: dms_kafka_trigger
                namespace: company.team

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Received {{ trigger.messagesCount }} messages from DMS Kafka"

                triggers:
                  - id: watch
                    type: io.kestra.plugin.huawei.dms.kafka.Trigger
                    bootstrapServers: "dms-instance-id.kafka.eu-west-101.myhuaweicloud.com:9093"
                    saslMechanism: PLAIN
                    username: "{{ secret('DMS_KAFKA_USERNAME') }}"
                    password: "{{ secret('DMS_KAFKA_PASSWORD') }}"
                    topic: my-topic
                    groupId: kestra-trigger-group
                    maxRecords: 50
                    interval: PT60S
                """
        )
    }
)
public class Trigger extends AbstractTrigger
    implements PollingTriggerInterface, TriggerOutput<Consume.Output>, DmsKafkaConnectionInterface {

    @Schema(title = "Polling interval.", description = "ISO-8601 duration between poll cycles, e.g. `PT60S` (default).")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Duration interval = Duration.ofSeconds(60);

    @Schema(
        title = "Kafka bootstrap servers.",
        description = "Comma-separated list of `host:port` pairs that the Kafka client uses for the initial " +
            "cluster connection. For DMS for Kafka, copy this value from the instance detail page in the console."
    )
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> bootstrapServers;

    @Schema(
        title = "SASL mechanism used for authentication.",
        description = """
            `PLAIN` — username/password (default, used by most DMS for Kafka instances).
            `SCRAM_SHA_512` — stronger challenge-response, supported on newer instances.
            `NONE` — no SASL; for VPC-internal clusters with no auth enabled.
            """
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<SaslMechanism> saslMechanism = Property.ofValue(SaslMechanism.PLAIN);

    @Schema(
        title = "SASL username.",
        description = "Required when `saslMechanism` is `PLAIN` or `SCRAM_SHA_512`."
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> username;

    @Schema(
        title = "SASL password.",
        description = "Required when `saslMechanism` is `PLAIN` or `SCRAM_SHA_512`. " +
            "**Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> password;

    @Schema(
        title = "Enable TLS for the Kafka connection.",
        description = "Set to `true` to use `SASL_SSL` instead of `SASL_PLAINTEXT`. " +
            "DMS for Kafka instances accessed over the public internet require TLS."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<Boolean> sslEnabled = Property.ofValue(false);

    @Schema(
        title = "Key serializer/deserializer type.",
        description = "`STRING` (default), `JSON`, or `BINARY`."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<SerdeType> keySerdeType = Property.ofValue(SerdeType.STRING);

    @Schema(
        title = "Value serializer/deserializer type.",
        description = "`STRING` (default), `JSON`, or `BINARY`."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<SerdeType> valueSerdeType = Property.ofValue(SerdeType.STRING);

    @Schema(title = "Kafka topic to consume from.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> topic;

    @Schema(title = "Consumer group ID used for offset tracking.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> groupId;

    @Schema(title = "Stop after consuming this many records per poll cycle.")
    @PluginProperty(group = "execution")
    private Property<Integer> maxRecords;

    @Schema(title = "Stop after this duration elapses per poll cycle.")
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration;

    @Schema(title = "Maximum time to wait for records during each poll call.")
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> pollDuration = Property.ofValue(Duration.ofSeconds(5));

    @AssertTrue(message = "At least one of 'maxRecords' or 'maxDuration' must be set")
    public boolean isMaxConstraintSet() {
        return maxRecords != null || maxDuration != null;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var rTopic = runContext.render(topic).as(String.class).orElseThrow();
        var rGroupId = runContext.render(groupId).as(String.class).orElseThrow();
        var rBootstrap = runContext.render(bootstrapServers).as(String.class).orElseThrow();

        var task = consumeTask();
        Consume.Output run;
        try {
            run = task.run(runContext);
        } catch (Exception e) {
            runContext.logger().error(
                "DMS Kafka trigger failed topic={} groupId={} bootstrap={}: {}",
                rTopic, rGroupId, rBootstrap, e.getMessage()
            );
            throw e;
        }

        runContext.logger().info(
            "DMS Kafka trigger polled topic={} groupId={} bootstrap={}: {} records",
            rTopic, rGroupId, rBootstrap, run.getMessagesCount()
        );

        if (run.getMessagesCount() == 0) {
            return Optional.empty();
        }

        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, run));
    }

    private Consume consumeTask() {
        return Consume.builder()
            .id(this.id)
            .type(Consume.class.getName())
            .bootstrapServers(this.bootstrapServers)
            .saslMechanism(this.saslMechanism)
            .username(this.username)
            .password(this.password)
            .sslEnabled(this.sslEnabled)
            .keySerdeType(this.keySerdeType)
            .valueSerdeType(this.valueSerdeType)
            .topic(this.topic)
            .groupId(this.groupId)
            .maxRecords(this.maxRecords)
            .maxDuration(this.maxDuration)
            .pollDuration(this.pollDuration)
            .build();
    }
}
