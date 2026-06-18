package io.kestra.plugin.huawei.dms.rocketmq.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.kestra.core.models.tasks.Output;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Represents a single DMS for RocketMQ message as returned by
 * {@link io.kestra.plugin.huawei.dms.rocketmq.Consume} and emitted by the triggers.
 */
@Builder
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message implements Output {

    @Schema(title = "Unique message ID assigned by the RocketMQ broker.")
    private final String messageId;

    @Schema(title = "Message body, deserialized according to serdeType.")
    private final Object body;

    @Schema(title = "Topic the message was published to.")
    private final String topic;

    @Schema(title = "Message tags used for server-side filtering.")
    private final String tags;

    @Schema(title = "Message keys (space-separated on the wire).")
    private final String keys;

    @Schema(title = "Timestamp (milliseconds since epoch) when the message was created on the producer side.")
    private final Long bornTimestamp;
}
