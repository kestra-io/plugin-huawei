package io.kestra.plugin.huawei.dms.kafka.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.kestra.core.models.tasks.Output;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents a single DMS Kafka message as returned by {@link io.kestra.plugin.huawei.dms.kafka.Consume}
 * and emitted by the triggers.
 */
@Builder
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message implements Output {

    @Schema(title = "Message key, deserialized according to keySerdeType")
    private final Object key;

    @Schema(title = "Message value, deserialized according to valueSerdeType")
    private final Object value;

    @Schema(title = "Kafka topic the message was consumed from")
    private final String topic;

    @Schema(title = "Partition the message was stored in")
    private final Integer partition;

    @Schema(title = "Offset of this message within its partition")
    private final Long offset;

    @Schema(title = "Timestamp assigned by the broker when the message was stored")
    private final Instant timestamp;

    @Schema(
        title = "Kafka headers attached to the message",
        description = "Each entry is a key/value pair; the value is a UTF-8 decoded string."
    )
    private final List<Map.Entry<String, String>> headers;
}
