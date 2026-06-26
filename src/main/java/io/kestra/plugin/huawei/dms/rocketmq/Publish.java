package io.kestra.plugin.huawei.dms.rocketmq;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Data;
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
import lombok.experimental.SuperBuilder;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Publish messages to a Huawei DMS for RocketMQ topic",
    description = """
        Reads messages from `from` and sends them to the configured topic using the Apache RocketMQ protocol.
        Supports `STRING` and `JSON` serializers for the message body. Each input map may contain
        `body`, `tags`, and `keys`. Sends are synchronous — the task only completes once all messages
        have been acknowledged by the broker.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: dms_rocketmq_publish
                namespace: company.team

                tasks:
                  - id: publish
                    type: io.kestra.plugin.huawei.dms.rocketmq.Publish
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    nameServerAddr: "dms-instance-id.rocketmq.eu-west-101.myhuaweicloud.com:8100"
                    topic: my-topic
                    groupId: kestra-producer-group
                    from:
                      body: "Hello from Kestra!"
                      tags: "order"
                """
        ),
        @Example(
            title = "Publish JSON messages",
            full = true,
            code = """
                id: dms_rocketmq_publish_json
                namespace: company.team

                tasks:
                  - id: publish
                    type: io.kestra.plugin.huawei.dms.rocketmq.Publish
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    nameServerAddr: "dms-instance-id.rocketmq.eu-west-101.myhuaweicloud.com:8100"
                    topic: orders
                    groupId: kestra-producer-group
                    serdeType: JSON
                    from:
                      body:
                        orderId: "order-123"
                        amount: 99.99
                      tags: "order"
                """
        )
    },
    metrics = {
        @Metric(name = "dms.rocketmq.publish.count", type = Counter.TYPE, unit = "messages",
            description = "Number of messages published to the DMS RocketMQ topic.")
    }
)
public class Publish extends AbstractDmsRocketMq implements RunnableTask<Publish.Output>, Data.From {

    @Schema(
        title = "Messages to publish",
        description = """
            A single map, a list of maps, or a URI pointing to an ION file in Kestra internal storage.
            Each map may contain: `body` (required), `tags`, and `keys`.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Object from;

    @Override
    @SuppressWarnings("unchecked")
    public Output run(RunContext runContext) throws Exception {
        var rTopic = runContext.render(topic).as(String.class).orElseThrow();
        var rGroupId = runContext.render(groupId).as(String.class).orElse("kestra-producer-group");
        var rSerdeType = runContext.render(serdeType).as(RocketMqSerdeType.class).orElse(RocketMqSerdeType.STRING);
        var rTags = runContext.render(tags).as(String.class).orElse("*");

        var producer = buildProducer(runContext, rGroupId);
        var count = 0;

        try {
            producer.start();

            for (var map : Data.from(from).read(runContext).toIterable()) {
                var bodyBytes = rSerdeType.serialize(map.get("body"));
                var msgTags = map.containsKey("tags") ? String.valueOf(map.get("tags")) : rTags;
                var msgKeys = map.containsKey("keys") ? String.valueOf(map.get("keys")) : null;

                var msg = new Message(rTopic, msgTags, bodyBytes);
                if (msgKeys != null) {
                    msg.setKeys(msgKeys);
                }

                var result = producer.send(msg);
                if (result.getSendStatus() != SendStatus.SEND_OK) {
                    throw new IllegalStateException(
                        "RocketMQ send failed for topic " + rTopic + ": status=" + result.getSendStatus()
                    );
                }
                count++;
            }
        } finally {
            producer.shutdown();
        }

        runContext.metric(Counter.of("dms.rocketmq.publish.count", count));
        runContext.logger().debug("Published {} messages to DMS RocketMQ topic {}", count, rTopic);

        return Output.builder().messagesCount(count).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Number of messages successfully published to the DMS RocketMQ topic")
        private final Integer messagesCount;
    }
}
