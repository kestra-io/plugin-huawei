package io.kestra.plugin.huawei.dms.rocketmq;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
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

    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> nameServerAddr;

    @PluginProperty(group = "connection")
    protected Property<String> instanceId;

    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> topic;

    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> groupId;

    @Builder.Default
    @PluginProperty(group = "processing")
    protected Property<String> tags = Property.ofValue("*");

    @Builder.Default
    @PluginProperty(group = "processing")
    protected Property<RocketMqSerdeType> serdeType = Property.ofValue(RocketMqSerdeType.STRING);

    /**
     * Creates a configured {@link DefaultMQProducer} with AK/SK ACL credentials.
     *
     * <p>The caller is responsible for starting and shutting down the producer.
     */
    protected DefaultMQProducer buildProducer(RunContext runContext, String producerGroup) throws IllegalVariableEvaluationException {
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
    protected DefaultMQPullConsumer buildPullConsumer(RunContext runContext, String consumerGroup) throws IllegalVariableEvaluationException {
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
    protected DefaultMQPushConsumer buildPushConsumer(RunContext runContext, String consumerGroup) throws IllegalVariableEvaluationException {
        var config = huaweiClientConfig(runContext);
        DefaultMQPushConsumer consumer;

        if (config.accessKeyId() != null && config.secretAccessKey() != null) {
            var hook = new AclClientRPCHook(new SessionCredentials(config.accessKeyId(), config.secretAccessKey()));
            consumer = new DefaultMQPushConsumer(consumerGroup, hook, null);
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
