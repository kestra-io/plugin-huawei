package io.kestra.plugin.huawei.dms.rocketmq;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.huawei.dms.rocketmq.models.Message;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.common.message.MessageQueue;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Set;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Consume messages from a Huawei DMS for RocketMQ topic.",
    description = """
        Polls the configured topic using pull-mode until `maxRecords` or `maxDuration` is reached
        (at least one is required). Messages are written to Kestra internal storage as ION at `uri`.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: dms_rocketmq_consume
                namespace: company.team

                tasks:
                  - id: consume
                    type: io.kestra.plugin.huawei.dms.rocketmq.Consume
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    nameServerAddr: "dms-instance-id.rocketmq.eu-west-101.myhuaweicloud.com:8100"
                    topic: my-topic
                    groupId: kestra-consumer-group
                    maxRecords: 50
                """
        )
    },
    metrics = {
        @Metric(name = "dms.rocketmq.consume.count", type = Counter.TYPE, unit = "messages",
            description = "Number of messages consumed from the DMS RocketMQ topic.")
    }
)
public class Consume extends AbstractDmsRocketMq implements RunnableTask<Consume.Output> {

    @Schema(
        title = "Stop after consuming this many messages.",
        description = "At least one of `maxRecords` or `maxDuration` must be set."
    )
    @PluginProperty(group = "execution")
    private Property<Integer> maxRecords;

    @Schema(
        title = "Stop after this duration has elapsed.",
        description = "ISO-8601 duration, e.g. `PT30S`. At least one of `maxRecords` or `maxDuration` must be set."
    )
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration;

    @Override
    public Output run(RunContext runContext) throws Exception {
        if (maxRecords == null && maxDuration == null) {
            throw new IllegalArgumentException("'maxRecords' or 'maxDuration' must be set to avoid an infinite loop");
        }

        var rTopic = runContext.render(topic).as(String.class).orElseThrow();
        var rGroupId = runContext.render(groupId).as(String.class).orElseThrow();
        var rTags = runContext.render(tags).as(String.class).orElse("*");
        var rSerdeType = runContext.render(serdeType).as(RocketMqSerdeType.class).orElse(RocketMqSerdeType.STRING);

        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        var total = 0;

        DefaultMQPullConsumer consumer = buildPullConsumer(runContext, rGroupId);
        try {
            consumer.start();
            Set<MessageQueue> mqs = consumer.fetchSubscribeMessageQueues(rTopic);
            var started = ZonedDateTime.now();

            outer:
            for (var mq : mqs) {
                var offset = consumer.fetchConsumeOffset(mq, false);
                if (offset < 0) {
                    offset = 0;
                }

                try (var output = new BufferedOutputStream(new FileOutputStream(tempFile, total > 0), FileSerde.BUFFER_SIZE)) {
                    while (true) {
                        var pullResult = consumer.pull(mq, rTags, offset, 32);
                        if (pullResult.getPullStatus() == PullStatus.FOUND) {
                            for (var msg : pullResult.getMsgFoundList()) {
                                var body = rSerdeType.deserialize(msg.getBody());
                                FileSerde.write(output, Message.builder()
                                    .messageId(msg.getMsgId())
                                    .body(body)
                                    .topic(msg.getTopic())
                                    .tags(msg.getTags())
                                    .keys(msg.getKeys())
                                    .bornTimestamp(msg.getBornTimestamp())
                                    .build());
                                total++;
                                if (isFinished(runContext, total, started)) {
                                    consumer.updateConsumeOffset(mq, pullResult.getNextBeginOffset());
                                    output.flush();
                                    break outer;
                                }
                            }
                            consumer.updateConsumeOffset(mq, pullResult.getNextBeginOffset());
                            offset = pullResult.getNextBeginOffset();
                        } else if (pullResult.getPullStatus() == PullStatus.NO_NEW_MSG ||
                            pullResult.getPullStatus() == PullStatus.NO_MATCHED_MSG) {
                            break;
                        } else {
                            break;
                        }

                        if (isFinished(runContext, total, started)) {
                            output.flush();
                            break outer;
                        }
                    }
                    output.flush();
                }
            }
        } finally {
            consumer.shutdown();
        }

        runContext.metric(Counter.of("dms.rocketmq.consume.count", total));
        runContext.logger().debug("Consumed {} messages from DMS RocketMQ topic {}", total, rTopic);

        return Output.builder()
            .messagesCount(total)
            .uri(runContext.storage().putFile(tempFile))
            .build();
    }

    private boolean isFinished(RunContext runContext, int count, ZonedDateTime start) throws Exception {
        var rMax = runContext.render(maxRecords).as(Integer.class);
        if (rMax.isPresent() && count >= rMax.get()) {
            return true;
        }
        var rDuration = runContext.render(maxDuration).as(Duration.class);
        return rDuration.isPresent() && ZonedDateTime.now().toEpochSecond() > start.plus(rDuration.get()).toEpochSecond();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Number of messages consumed from the DMS RocketMQ topic.")
        private final Integer messagesCount;

        @Schema(title = "URI of the ION file in Kestra internal storage containing the consumed messages.")
        private final URI uri;
    }
}
