package io.kestra.plugin.huawei.dms.kafka;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.RealtimeTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.plugin.huawei.dms.kafka.models.Message;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.kafka.common.errors.WakeupException;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow for each message on a Huawei DMS for Kafka topic.",
    description = """
        Maintains a persistent Kafka consumer and fires one Kestra execution per record as messages arrive.
        Offset commits happen after each record is processed to provide at-least-once semantics.
        For batched interval-based consumption use `Trigger`.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: dms_kafka_realtime_trigger
                namespace: company.team

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.value }}"

                triggers:
                  - id: realtime
                    type: io.kestra.plugin.huawei.dms.kafka.RealtimeTrigger
                    bootstrapServers: "dms-instance-id.kafka.eu-west-101.myhuaweicloud.com:9093"
                    saslMechanism: PLAIN
                    username: "{{ secret('DMS_KAFKA_USERNAME') }}"
                    password: "{{ secret('DMS_KAFKA_PASSWORD') }}"
                    topic: my-topic
                    groupId: kestra-realtime-group
                """
        )
    }
)
public class RealtimeTrigger extends AbstractTrigger
    implements RealtimeTriggerInterface, TriggerOutput<Message>, DmsKafkaConnectionInterface {

    // Finite poll duration prevents busy-spinning when the broker is idle or unreachable.
    private static final Duration POLL_DURATION = Duration.ofSeconds(2);

    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> bootstrapServers;

    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<SaslMechanism> saslMechanism = Property.ofValue(SaslMechanism.PLAIN);

    @PluginProperty(group = "connection", secret = true)
    private Property<String> username;

    @PluginProperty(group = "connection", secret = true)
    private Property<String> password;

    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<Boolean> sslEnabled = Property.ofValue(false);

    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<SerdeType> keySerdeType = Property.ofValue(SerdeType.STRING);

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

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final CountDownLatch waitForTermination = new CountDownLatch(1);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final AtomicReference<org.apache.kafka.clients.consumer.KafkaConsumer<byte[], byte[]>> kafkaConsumerRef =
        new AtomicReference<>();

    @Override
    public Publisher<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var consumeTask = buildConsumeTask();

        return Flux.create(sink -> {
            var logger = runContext.logger();
            try {
                var rTopic = runContext.render(topic).as(String.class).orElseThrow();
                var rGroupId = runContext.render(groupId).as(String.class).orElseThrow();
                var rKeySerdeType = runContext.render(keySerdeType).as(SerdeType.class).orElse(SerdeType.STRING);
                var rValueSerdeType = runContext.render(valueSerdeType).as(SerdeType.class).orElse(SerdeType.STRING);

                logger.debug("Starting DMS Kafka realtime trigger triggerId={} topic={} groupId={}", this.id, rTopic, rGroupId);

                try (var consumer = consumeTask.consumer(runContext, rGroupId)) {
                    kafkaConsumerRef.set(consumer);
                    consumer.subscribe(List.of(rTopic));
                    logger.debug("DMS Kafka realtime trigger subscribed triggerId={}", this.id);

                    while (isActive.get()) {
                        var records = consumer.poll(POLL_DURATION);
                        for (var record : records) {
                            var msg = consumeTask.toMessage(record, rKeySerdeType, rValueSerdeType);
                            sink.next(TriggerService.generateRealtimeExecution(this, conditionContext, context, msg));
                        }
                        if (!records.isEmpty()) {
                            consumer.commitSync();
                        }
                        if (Thread.currentThread().isInterrupted()) {
                            isActive.set(false);
                        }
                    }
                }
            } catch (WakeupException e) {
                logger.debug("DMS Kafka realtime trigger triggerId={} woken up; stopping poll loop", this.id);
            } catch (Exception e) {
                logger.error("DMS Kafka realtime trigger triggerId={} failed: {}", this.id, e.getMessage());
                sink.error(e);
            } finally {
                sink.complete();
                waitForTermination.countDown();
            }
        });
    }

    @Override
    public void kill() {
        stop(true);
    }

    @Override
    public void stop() {
        stop(false);
    }

    private void stop(boolean wait) {
        if (!isActive.compareAndSet(true, false)) {
            return;
        }
        org.slf4j.LoggerFactory.getLogger(RealtimeTrigger.class)
            .debug("Stopping DMS Kafka realtime trigger triggerId={} (wait={})", this.id, wait);
        Optional.ofNullable(kafkaConsumerRef.get()).ifPresent(c -> c.wakeup());
        if (wait) {
            try {
                waitForTermination.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Consume buildConsumeTask() {
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
            .maxRecords(Property.ofValue(1))
            .build();
    }
}
