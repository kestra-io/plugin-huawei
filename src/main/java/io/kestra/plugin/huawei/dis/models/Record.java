package io.kestra.plugin.huawei.dis.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.kestra.core.models.tasks.Output;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Represents a single DIS record as returned by {@link io.kestra.plugin.huawei.dis.Consume} and
 * emitted by the triggers.
 */
@Builder
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class Record implements Output {

    @Schema(title = "Id of the partition (shard) the record was read from")
    private final String partitionId;

    @Schema(title = "Sequence number assigned to the record by DIS")
    private final String sequenceNumber;

    @Schema(title = "Partition key used to route the record when it was written")
    private final String partitionKey;

    @Schema(title = "Record data, deserialized according to `serdeType`")
    private final Object data;

    @Schema(title = "Timestamp assigned when the record was written to the stream")
    private final Instant timestamp;
}
