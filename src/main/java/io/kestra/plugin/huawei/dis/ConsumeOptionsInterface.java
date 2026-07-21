package io.kestra.plugin.huawei.dis;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Shared consume configuration for DIS record-consumption operations.
 *
 * <p>Implemented by every DIS consumption task/trigger (Consume, Trigger, RealtimeTrigger) so they
 * share a single property schema, mirroring {@code io.kestra.plugin.huawei.obs.ListInterface}.
 */
public interface ConsumeOptionsInterface {

    @Schema(title = "DIS stream name")
    @NotNull
    @PluginProperty(group = "main")
    Property<String> getStreamName();

    @Schema(
        title = "Single partition to consume from",
        description = "When omitted (default), every partition of the stream is consumed."
    )
    @PluginProperty(group = "main")
    Property<String> getPartitionId();

    @Schema(
        title = "Where to start reading a partition when no earlier position is known",
        description = """
            `TRIM_HORIZON` (default) reads from the oldest retained record. `LATEST` reads only new
            records. `AT_TIMESTAMP` reads from the oldest record at or after `startingTimestamp`
            (required in that case). Triggers apply this only the first time a partition has no
            persisted watermark yet.
            """
    )
    @PluginProperty(group = "main")
    Property<StartingPosition> getStartingPosition();

    @Schema(title = "Starting timestamp", description = "Required when `startingPosition` is `AT_TIMESTAMP`.")
    @PluginProperty(group = "main")
    Property<Instant> getStartingTimestamp();

    @Schema(title = "Deserialization type applied to each record's `data` value")
    @PluginProperty(group = "processing")
    Property<SerdeType> getSerdeType();

    @Schema(
        title = "Maximum bytes fetched per partition, per call",
        description = "Defaults to 2 MB (2097152 bytes), DIS's per-request payload ceiling."
    )
    @PluginProperty(group = "execution")
    Property<Integer> getMaxFetchBytes();
}
