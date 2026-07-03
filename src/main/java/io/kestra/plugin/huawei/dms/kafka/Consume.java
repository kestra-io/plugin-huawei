package io.kestra.plugin.huawei.dms.kafka;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.huawei.dms.kafka.models.Message;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Consume messages from a Huawei DMS for Kafka topic",
    description = """
        Polls the configured topic until `maxRecords` or `maxDuration` is reached (at least one is required),
        or until the topic is fully drained — whichever comes first. The task stops early when all assigned
        partitions have been read to their current end offset, so `maxRecords` acts as an upper bound rather
        than a target the task will block waiting for. Messages are written to Kestra internal storage as ION
        at `uri`. Offsets are committed after all records have been written, ensuring at-least-once delivery
        semantics.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: dms_kafka_consume
                namespace: company.team

                tasks:
                  - id: consume
                    type: io.kestra.plugin.huawei.dms.kafka.Consume
                    bootstrapServers: "dms-instance-id.kafka.eu-west-101.myhuaweicloud.com:9093"
                    saslMechanism: PLAIN
                    username: "{{ secret('DMS_KAFKA_USERNAME') }}"
                    password: "{{ secret('DMS_KAFKA_PASSWORD') }}"
                    topic: my-topic
                    groupId: kestra-consumer-group
                    maxRecords: 100
                    valueSerdeType: JSON
                """
        )
    },
    metrics = {
        @Metric(name = "dms.kafka.consume.count", type = Counter.TYPE, unit = "records",
            description = "Number of records consumed from the DMS Kafka topic.")
    }
)
public class Consume extends AbstractDmsKafka implements RunnableTask<Consume.Output> {

    @Schema(title = "Kafka topic to consume from")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> topic;

    @Schema(
        title = "Consumer group ID",
        description = "Identifies the consumer group used for offset tracking. Multiple tasks with the same " +
            "group ID share the topic partitions."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> groupId;

    @Schema(
        title = "Stop after consuming this many records",
        description = """
            Upper bound on the number of records to consume. The task may return fewer records if the topic
            is drained before this limit is reached. At least one of `maxRecords` or `maxDuration` must be set.
            """
    )
    @PluginProperty(group = "execution")
    private Property<Integer> maxRecords;

    @Schema(
        title = "Stop after this duration has elapsed",
        description = "ISO-8601 duration, e.g. `PT30S`. At least one of `maxRecords` or `maxDuration` must be set."
    )
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration;

    @Builder.Default
    @Schema(
        title = "How long each poll waits for new records",
        description = "ISO-8601 duration, e.g. `PT5S` (default). Shorter values reduce latency; longer values reduce CPU load."
    )
    @PluginProperty(group = "execution")
    private Property<Duration> pollDuration = Property.ofValue(Duration.ofSeconds(5));

    @Override
    public Output run(RunContext runContext) throws Exception {
        if (maxRecords == null && maxDuration == null) {
            throw new IllegalArgumentException("'maxRecords' or 'maxDuration' must be set to avoid an infinite loop");
        }

        var rTopic = runContext.render(topic).as(String.class).orElseThrow();
        var rGroupId = runContext.render(groupId).as(String.class).orElseThrow();
        var rKeySerdeType = runContext.render(keySerdeType).as(SerdeType.class).orElse(SerdeType.STRING);
        var rValueSerdeType = runContext.render(valueSerdeType).as(SerdeType.class).orElse(SerdeType.STRING);
        var rPollDuration = runContext.render(pollDuration).as(Duration.class).orElse(Duration.ofSeconds(5));
        var rMaxRecords = runContext.render(maxRecords).as(Integer.class).orElse(null);
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(null);

        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        var total = 0;

        try (
            var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE);
            var consumer = consumer(runContext, rGroupId)
        ) {
            consumer.subscribe(List.of(rTopic));
            var started = ZonedDateTime.now();

            boolean finished;
            do {
                var records = consumer.poll(rPollDuration);
                for (var record : records) {
                    FileSerde.write(output, toMessage(record, rKeySerdeType, rValueSerdeType));
                    total++;
                }
                finished = isFinished(rMaxRecords, rMaxDuration, total, started);
                if (!finished && records.isEmpty()) {
                    finished = isDrained(consumer);
                }
            } while (!finished);

            output.flush();
            consumer.commitSync();
        }

        runContext.metric(Counter.of("dms.kafka.consume.count", total));
        runContext.logger().debug("Consumed {} records from DMS Kafka topic {}", total, rTopic);

        return Output.builder()
            .messagesCount(total)
            .uri(runContext.storage().putFile(tempFile))
            .build();
    }

    private boolean isFinished(Integer rMax, Duration rDuration, int count, ZonedDateTime start) {
        if (rMax != null && count >= rMax) {
            return true;
        }
        if (rDuration != null && ZonedDateTime.now().toEpochSecond() > start.plus(rDuration).toEpochSecond()) {
            return true;
        }
        return count == 0 && rDuration == null && rMax == null;
    }

    /**
     * Returns true when every assigned partition's current position has reached the end offset,
     * meaning the topic is fully drained. An empty assignment (before the first poll triggers
     * group coordination) is treated as not-yet-drained to avoid a false early exit.
     */
    private boolean isDrained(KafkaConsumer<byte[], byte[]> consumer) {
        var assignment = consumer.assignment();
        if (assignment.isEmpty()) {
            return false;
        }
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(assignment);
        for (var tp : assignment) {
            var end = endOffsets.getOrDefault(tp, 0L);
            if (consumer.position(tp) < end) {
                return false;
            }
        }
        return true;
    }

    Message toMessage(ConsumerRecord<byte[], byte[]> record, SerdeType keyType, SerdeType valueType) throws Exception {
        return Message.builder()
            .key(keyType.deserialize(record.key()))
            .value(valueType.deserialize(record.value()))
            .topic(record.topic())
            .partition(record.partition())
            .offset(record.offset())
            .timestamp(Instant.ofEpochMilli(record.timestamp()))
            .headers(headersToList(record))
            .build();
    }

    private static List<java.util.Map.Entry<String, String>> headersToList(ConsumerRecord<byte[], byte[]> record) {
        var result = new ArrayList<java.util.Map.Entry<String, String>>();
        for (Header h : record.headers()) {
            result.add(new AbstractMap.SimpleImmutableEntry<>(
                h.key(),
                h.value() != null ? new String(h.value(), StandardCharsets.UTF_8) : null
            ));
        }
        return result;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Number of records consumed from the DMS Kafka topic")
        private final Integer messagesCount;

        @Schema(title = "URI of the ION file in Kestra internal storage containing the consumed messages")
        private final URI uri;
    }
}
