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
import io.kestra.plugin.huawei.AbstractConnectionInterface;
import io.kestra.plugin.huawei.TemporaryCredentialsConfig;
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
@ToString(exclude = {"accessKeyId", "secretAccessKey", "securityToken", "temporaryCredentials"})
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
    implements RealtimeTriggerInterface, TriggerOutput<Message>, DmsRocketMqConnectionInterface, AbstractConnectionInterface {

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
    @NotNull
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

    @Schema(
        title = "Access Key (AK) used to authenticate with Huawei Cloud.",
        description = "Huawei Cloud access key used together with `secretAccessKey` to sign API requests. " +
            "Required for AK/SK-based authentication; not required when " +
            "providing a pre-obtained `securityToken`. **Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> accessKeyId;

    @Schema(
        title = "Secret Key (SK) used to authenticate with Huawei Cloud.",
        description = "Huawei Cloud secret key paired with `accessKeyId`. " +
            "Required for AK/SK-based authentication. **Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> secretAccessKey;

    @Schema(
        title = "Pre-obtained Huawei Cloud IAM token used as bearer credential for downstream API calls.",
        description = "When set, downstream Huawei tasks send this value in the `X-Auth-Token` header instead of " +
            "signing requests with AK/SK. **Sensitive.**"
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> securityToken;

    @Schema(
        title = "Huawei Cloud Project ID.",
        description = "Identifies the region-scoped project against which most regional services authenticate. " +
            "Mutually exclusive with `domainId` for global services such as IAM."
    )
    @PluginProperty(group = "connection")
    private Property<String> projectId;

    @Schema(
        title = "Huawei Cloud Account Domain ID.",
        description = "Identifies the Huawei Cloud account (domain). Required when authenticating against global " +
            "services such as IAM, or when requesting a domain-scoped IAM token."
    )
    @PluginProperty(group = "connection")
    private Property<String> domainId;

    @Schema(
        title = "Huawei Cloud region.",
        description = "Region identifier such as `eu-west-101`, `ap-southeast-1`, or `cn-north-4`."
    )
    @PluginProperty(group = "connection")
    private Property<String> region;

    @Schema(
        title = "Inline IAM credential exchange.",
        description = """
            When set, the connection layer calls the Huawei IAM STS API once per task execution and
            uses the returned temporary AK/SK + security token instead of the static `accessKeyId`
            and `secretAccessKey` properties.

            Configure once via `pluginDefaults` to apply transparently to every task in a namespace
            without per-task credential wiring:

            ```yaml
            pluginDefaults:
              - type: io.kestra.plugin.huawei.obs
                values:
                  region: eu-west-101
                  temporaryCredentials:
                    authMethod: PASSWORD
                    username: my-iam-user
                    password: "{{ secret('HUAWEI_IAM_PASSWORD') }}"
                    domainName: my-account-domain
                    durationSeconds: 3600
            ```

            **Long-running tasks:** the exchange runs once at execution start. For `RealtimeTrigger`
            or long-running `Consume` tasks that outlive `durationSeconds`, credentials will expire
            mid-run. Use long-lived AK/SK properties or refresh externally in that case.
            """
    )
    @PluginProperty(group = "connection")
    private Property<TemporaryCredentialsConfig> temporaryCredentials;

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

    @Builder.Default
    @Getter(AccessLevel.NONE)
    private final AtomicReference<org.slf4j.Logger> loggerRef = new AtomicReference<>();

    @Override
    public Publisher<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        loggerRef.set(runContext.logger());

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
        var logger = loggerRef.get();
        if (logger != null) {
            logger.debug("Stopping DMS RocketMQ realtime trigger triggerId={} (wait={})", this.id, wait);
        }
        stopSignal.countDown();
        if (wait) {
            try {
                doneLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private DefaultMQPushConsumer buildPushConsumer(RunContext runContext, String consumerGroup) throws Exception {
        var config = huaweiClientConfig(runContext);
        DefaultMQPushConsumer consumer;

        if (config.accessKeyId() != null && config.secretAccessKey() != null) {
            var hook = new AclClientRPCHook(new SessionCredentials(config.accessKeyId(), config.secretAccessKey()));
            consumer = new DefaultMQPushConsumer(consumerGroup, hook, new AllocateMessageQueueAveragely());
        } else {
            consumer = new DefaultMQPushConsumer(consumerGroup);
        }

        var rNameServer = runContext.render(nameServerAddr).as(String.class).orElseThrow();
        consumer.setNamesrvAddr(rNameServer);

        var rInstanceId = runContext.render(instanceId).as(String.class).orElse(null);
        if (rInstanceId != null && !rInstanceId.isBlank()) {
            consumer.setNamespace(rInstanceId);
        }
        return consumer;
    }
}
