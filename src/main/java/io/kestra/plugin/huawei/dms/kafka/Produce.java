package io.kestra.plugin.huawei.dms.kafka;

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
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Publish messages to a Huawei DMS for Kafka topic",
    description = """
        Reads messages from `from` and sends them to the configured topic using the standard Apache Kafka protocol.
        Supports `STRING`, `JSON`, and `BINARY` serializers for both key and value. Each input map may contain
        `key`, `value`, `topic` (overrides the task-level topic), `partition`, and `headers`.
        All sends flush before the task exits.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: dms_kafka_produce
                namespace: company.team

                tasks:
                  - id: produce
                    type: io.kestra.plugin.huawei.dms.kafka.Produce
                    bootstrapServers: "dms-instance-id.kafka.eu-west-101.myhuaweicloud.com:9093"
                    saslMechanism: PLAIN
                    username: "{{ secret('DMS_KAFKA_USERNAME') }}"
                    password: "{{ secret('DMS_KAFKA_PASSWORD') }}"
                    topic: my-topic
                    from:
                      key: "order-123"
                      value: "Hello from Kestra!"
                """
        ),
        @Example(
            title = "Produce JSON messages from a file",
            full = true,
            code = """
                id: dms_kafka_produce_json
                namespace: company.team

                inputs:
                  - id: dataFile
                    type: FILE

                tasks:
                  - id: produce
                    type: io.kestra.plugin.huawei.dms.kafka.Produce
                    bootstrapServers: "dms-instance-id.kafka.eu-west-101.myhuaweicloud.com:9093"
                    saslMechanism: PLAIN
                    username: "{{ secret('DMS_KAFKA_USERNAME') }}"
                    password: "{{ secret('DMS_KAFKA_PASSWORD') }}"
                    topic: orders
                    valueSerdeType: JSON
                    from: "{{ inputs.dataFile }}"
                """
        )
    },
    metrics = {
        @Metric(name = "dms.kafka.produce.count", type = Counter.TYPE, unit = "records",
            description = "Number of records sent to the DMS Kafka topic.")
    }
)
public class Produce extends AbstractDmsKafka implements RunnableTask<Produce.Output>, Data.From {

    @Schema(
        title = "Target Kafka topic",
        description = "The topic to produce messages to. Can be overridden per record by setting `topic` inside `from`."
    )
    @PluginProperty(group = "main")
    private Property<String> topic;

    @Schema(
        title = "Messages to produce",
        description = """
            A single map, a list of maps, or a URI pointing to an ION file in Kestra internal storage.
            Each map may contain: `key`, `value`, `topic` (overrides the task-level topic), `partition`, and `headers`.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Object from;

    @Override
    @SuppressWarnings("unchecked")
    public Output run(RunContext runContext) throws Exception {
        var rTopic = runContext.render(topic).as(String.class).orElse(null);
        var rKeySerdeType = runContext.render(keySerdeType).as(SerdeType.class).orElse(SerdeType.STRING);
        var rValueSerdeType = runContext.render(valueSerdeType).as(SerdeType.class).orElse(SerdeType.STRING);

        var count = 0;
        try (var producer = producer(runContext)) {
            var sendError = new AtomicReference<Exception>();
            for (var map : Data.from(from).read(runContext).toIterable()) {
                var recordTopic = map.containsKey("topic")
                    ? String.valueOf(map.get("topic"))
                    : rTopic;
                if (recordTopic == null) {
                    throw new IllegalArgumentException("Topic must be set either on the task or inside each message map");
                }

                var keyBytes = rKeySerdeType.serialize(map.get("key"));
                var valueBytes = rValueSerdeType.serialize(map.get("value"));
                var partition = (Integer) map.get("partition");
                var headers = buildHeaders(map.get("headers"));

                producer.send(
                    new ProducerRecord<>(recordTopic, partition, null, keyBytes, valueBytes, headers),
                    (metadata, exception) -> { if (exception != null) sendError.compareAndSet(null, exception); }
                );
                count++;
            }
            producer.flush();
            if (sendError.get() != null) {
                throw sendError.get();
            }
        }

        runContext.metric(Counter.of("dms.kafka.produce.count", count));
        runContext.logger().debug("Produced {} records to DMS Kafka", count);

        return Output.builder().messagesCount(count).build();
    }

    @SuppressWarnings("unchecked")
    private Iterable<Header> buildHeaders(Object headers) {
        if (headers == null) {
            return List.of();
        }
        if (headers instanceof Map<?, ?>) {
            return ((Map<String, String>) headers).entrySet().stream()
                .<Header>map(e -> new RecordHeader(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8)))
                .toList();
        }
        return List.of();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Number of records successfully sent to the DMS Kafka topic")
        private final Integer messagesCount;
    }
}
