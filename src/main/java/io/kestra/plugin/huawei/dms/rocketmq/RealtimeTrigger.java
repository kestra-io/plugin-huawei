package io.kestra.plugin.huawei.dms.rocketmq;

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
import io.kestra.core.runners.RunContext;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.plugin.huawei.dms.rocketmq.models.Message;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.consumer.rebalance.AllocateMessageQueueAveragely;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow for each message on a Huawei DMS for RocketMQ topic.",
    description = """
        Registers a push listener on the configured topic and fires one Kestra execution per message.
        The consumer is cleanly shut down when `kill()` or `stop()` is called.
        For batched interval-based consumption use `Trigger`.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: dms_rocketmq_realtime_trigger
                namespace: company.team

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.body }}"

                triggers:
                  - id: realtime
                    type: io.kestra.plugin.huawei.dms.rocketmq.RealtimeTrigger
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    nameServerAddr: "dms-instance-id.rocketmq.eu-west-101.myhuaweicloud.com:8100"
                    topic: my-topic
                    groupId: kestra-realtime-group
                """
        )
    }
)
public class RealtimeTrigger extends AbstractTrigger
    implements RealtimeTriggerInterface, TriggerOutput<Message>, DmsRocketMqConnectionInterface {

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

    // accessKeyId and secretAccessKey for the ACL hook come from AbstractConnection via the Consume task

    @Schema(
        title = "Huawei Cloud access key ID.",
        description = "AK credential for the ACL authentication hook. **Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @Builder.Default
    @PluginProperty(group = "connection", secret = true)
    private Property<String> accessKeyId = null;

    @Schema(
        title = "Huawei Cloud secret access key.",
        description = "SK credential for the ACL authentication hook. **Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @Builder.Default
    @PluginProperty(group = "connection", secret = true)
    private Property<String> secretAccessKey = null;

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    // stopSignal: counted down by stop() to wake the Flux await(); doneLatch: counted down by the
    // Flux finally block so stop(wait=true) can confirm full teardown.
    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final CountDownLatch doneLatch = new CountDownLatch(1);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final AtomicReference<DefaultMQPushConsumer> consumerRef = new AtomicReference<>();

    @Override
    public Publisher<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();

        var rTopic = runContext.render(topic).as(String.class).orElseThrow();
        var rGroupId = runContext.render(groupId).as(String.class).orElseThrow();
        var rTags = runContext.render(tags).as(String.class).orElse("*");
        var rSerdeType = runContext.render(serdeType).as(RocketMqSerdeType.class).orElse(RocketMqSerdeType.STRING);

        return Flux.create(sink -> {
            var logger = runContext.logger();
            DefaultMQPushConsumer consumer = null;
            try {
                consumer = buildPushConsumer(runContext, rGroupId);
                consumerRef.set(consumer);

                logger.info("Starting DMS RocketMQ realtime trigger triggerId={} topic={} groupId={}", this.id, rTopic, rGroupId);

                consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
                consumer.subscribe(rTopic, rTags);

                consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
                    for (var msg : msgs) {
                        if (!isActive.get()) {
                            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                        try {
                            var body = rSerdeType.deserialize(msg.getBody());
                            var message = Message.builder()
                                .messageId(msg.getMsgId())
                                .body(body)
                                .topic(msg.getTopic())
                                .tags(msg.getTags())
                                .keys(msg.getKeys())
                                .bornTimestamp(msg.getBornTimestamp())
                                .build();
                            sink.next(TriggerService.generateRealtimeExecution(this, conditionContext, context, message));
                        } catch (Exception e) {
                            logger.error("DMS RocketMQ realtime trigger error processing message: {}", e.getMessage());
                            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                    }
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                });

                consumer.start();
                logger.info("DMS RocketMQ realtime trigger started triggerId={} topic={} groupId={}", this.id, rTopic, rGroupId);

                // Block until stop()/kill() counts down stopSignal.
                stopSignal.await();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("DMS RocketMQ realtime trigger triggerId={} topic={} groupId={} failed: {}", this.id, rTopic, rGroupId, e.getMessage());
                sink.error(e);
            } finally {
                if (consumer != null) {
                    try {
                        consumer.shutdown();
                    } catch (Exception ignored) {
                    }
                }
                sink.complete();
                // Signal that teardown is fully complete so stop(wait=true) can return.
                doneLatch.countDown();
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
            .debug("Stopping DMS RocketMQ realtime trigger triggerId={} (wait={})", this.id, wait);
        stopSignal.countDown();
        if (wait) {
            try {
                doneLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private DefaultMQPushConsumer buildPushConsumer(RunContext runContext, String consumerGroup) throws IllegalVariableEvaluationException {
        var rNameServer = runContext.render(nameServerAddr).as(String.class).orElseThrow();
        var rAk = runContext.render(accessKeyId).as(String.class).orElse(null);
        var rSk = runContext.render(secretAccessKey).as(String.class).orElse(null);
        var rInstanceId = runContext.render(instanceId).as(String.class).orElse(null);

        DefaultMQPushConsumer consumer;
        if (rAk != null && rSk != null) {
            var hook = new AclClientRPCHook(new SessionCredentials(rAk, rSk));
            consumer = new DefaultMQPushConsumer(consumerGroup, hook, new AllocateMessageQueueAveragely());
        } else {
            consumer = new DefaultMQPushConsumer(consumerGroup);
        }

        consumer.setNamesrvAddr(rNameServer);
        if (rInstanceId != null && !rInstanceId.isBlank()) {
            consumer.setNamespace(rInstanceId);
        }
        return consumer;
    }
}
