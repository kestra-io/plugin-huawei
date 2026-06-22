package io.kestra.plugin.huawei.dms.rocketmq;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.rebalance.AllocateMessageQueueAveragely;
import org.apache.rocketmq.client.producer.DefaultMQProducer;

/**
 * Base class for all DMS for RocketMQ tasks and triggers.
 *
 * <p>Builds the {@link DefaultMQProducer} / {@link DefaultMQPullConsumer} from the DMS connection
 * properties. AK/SK credentials are passed via an {@link AclClientRPCHook} and are never logged.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractDmsRocketMq extends AbstractConnection implements DmsRocketMqConnectionInterface {

    @Schema(
        title = "Name server address.",
        description = "Address of the RocketMQ name server, e.g. `dms-host:8100`. For DMS for RocketMQ, " +
            "copy the name server address from the instance detail page in the Huawei Cloud console."
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> nameServerAddr;

    @Schema(
        title = "DMS instance ID.",
        description = "Huawei Cloud DMS for RocketMQ instance ID. Required when the instance uses instance isolation. " +
            "Leave empty for shared DMS instances."
    )
    @PluginProperty(group = "connection")
    protected Property<String> instanceId;

    @Schema(title = "Topic to publish to or consume from.")
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> topic;

    @Schema(
        title = "Consumer or producer group ID.",
        description = "Consumer group name for Consume/Trigger tasks; producer group name for Publish tasks."
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> groupId;

    @Schema(
        title = "Tag filter expression.",
        description = "Server-side filter applied by the broker. Use `*` (default) to receive all tags, " +
            "or a specific tag to filter messages."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    protected Property<String> tags = Property.ofValue("*");

    @Schema(
        title = "Message body serializer/deserializer.",
        description = "`STRING` (default) or `JSON`."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    protected Property<RocketMqSerdeType> serdeType = Property.ofValue(RocketMqSerdeType.STRING);

    /**
     * Creates a configured {@link DefaultMQProducer} with AK/SK ACL credentials.
     *
     * <p>The caller is responsible for starting and shutting down the producer.
     */
    protected DefaultMQProducer buildProducer(RunContext runContext, String producerGroup) throws Exception {
        var config = huaweiClientConfig(runContext);
        DefaultMQProducer producer;

        if (config.accessKeyId() != null && config.secretAccessKey() != null) {
            var hook = new AclClientRPCHook(new SessionCredentials(config.accessKeyId(), config.secretAccessKey()));
            producer = new DefaultMQProducer(producerGroup, hook);
        } else {
            producer = new DefaultMQProducer(producerGroup);
        }

        var rNameServer = runContext.render(nameServerAddr).as(String.class).orElseThrow();
        producer.setNamesrvAddr(rNameServer);

        var rInstanceId = runContext.render(instanceId).as(String.class).orElse(null);
        if (rInstanceId != null && !rInstanceId.isBlank()) {
            producer.setNamespace(rInstanceId);
        }

        return producer;
    }

    /**
     * Creates a configured {@link DefaultMQPullConsumer} with AK/SK ACL credentials.
     *
     * <p>The caller is responsible for starting and shutting down the consumer.
     */
    protected DefaultMQPullConsumer buildPullConsumer(RunContext runContext, String consumerGroup) throws Exception {
        var config = huaweiClientConfig(runContext);
        DefaultMQPullConsumer consumer;

        if (config.accessKeyId() != null && config.secretAccessKey() != null) {
            var hook = new AclClientRPCHook(new SessionCredentials(config.accessKeyId(), config.secretAccessKey()));
            consumer = new DefaultMQPullConsumer(consumerGroup, hook);
        } else {
            consumer = new DefaultMQPullConsumer(consumerGroup);
        }

        var rNameServer = runContext.render(nameServerAddr).as(String.class).orElseThrow();
        consumer.setNamesrvAddr(rNameServer);

        var rInstanceId = runContext.render(instanceId).as(String.class).orElse(null);
        if (rInstanceId != null && !rInstanceId.isBlank()) {
            consumer.setNamespace(rInstanceId);
        }

        return consumer;
    }

    /**
     * Creates a configured {@link DefaultMQPushConsumer} with AK/SK ACL credentials.
     *
     * <p>The caller is responsible for starting and shutting down the consumer.
     */
    protected DefaultMQPushConsumer buildPushConsumer(RunContext runContext, String consumerGroup) throws Exception {
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
