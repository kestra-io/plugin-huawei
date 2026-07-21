package io.kestra.plugin.huawei.dis;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.serializers.FileSerde;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow when new records arrive on a Huawei Cloud DIS (Data Ingestion Service) stream",
    description = """
        Polls the configured stream on a fixed interval and fires one execution per batch when new
        records are found. Records are stored at `{{ trigger.uri }}` in Kestra internal storage;
        `{{ trigger.count }}` gives the batch size.

        A per-partition sequence-number watermark is persisted in the flow's namespace
        [KV Store](https://kestra.io/docs/concepts/kv-store) between polls, so records already
        delivered are never re-delivered on a later poll. For one-execution-per-record semantics
        use `RealtimeTrigger`.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: dis_trigger
                namespace: company.team

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Received {{ trigger.count }} records from DIS"

                triggers:
                  - id: watch
                    type: io.kestra.plugin.huawei.dis.Trigger
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    streamName: my-stream
                    maxRecords: 500
                    interval: PT60S
                """
        )
    }
)
public class Trigger extends AbstractDisTrigger
    implements PollingTriggerInterface, TriggerOutput<Consume.Output>, ConsumeOptionsInterface {

    @Schema(title = "Polling interval", description = "ISO-8601 duration between poll cycles, e.g. `PT60S` (default).")
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Duration interval = Duration.ofSeconds(60);

    @NotNull
    @PluginProperty(group = "main")
    private Property<String> streamName;

    @PluginProperty(group = "main")
    private Property<String> partitionId;

    @Builder.Default
    @PluginProperty(group = "main")
    private Property<StartingPosition> startingPosition = Property.ofValue(StartingPosition.TRIM_HORIZON);

    @PluginProperty(group = "main")
    private Property<Instant> startingTimestamp;

    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<SerdeType> serdeType = Property.ofValue(SerdeType.STRING);

    @Schema(
        title = "Stop after consuming this many records per poll cycle",
        description = "Maximum " + Consume.MAX_RECORDS_HARD_CAP + ". At least one of `maxRecords` or `maxDuration` must be set."
    )
    @PluginProperty(group = "execution")
    private Property<Integer> maxRecords;

    @Schema(
        title = "Stop after this duration elapses per poll cycle",
        description = "ISO-8601 duration. Maximum `PT24H`. At least one of `maxRecords` or `maxDuration` must be set."
    )
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration;

    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Integer> maxFetchBytes = Property.ofValue(Consume.MAX_FETCH_BYTES_HARD_CAP);

    @AssertTrue(message = "At least one of 'maxRecords' or 'maxDuration' must be set")
    public boolean isMaxConstraintSet() {
        return maxRecords != null || maxDuration != null;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext triggerContext) throws Exception {
        var runContext = conditionContext.getRunContext();

        var rStreamName = runContext.render(streamName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("'streamName' is required"));
        var rPartitionId = runContext.render(partitionId).as(String.class).orElse(null);
        var rStartingPosition = runContext.render(startingPosition).as(StartingPosition.class).orElse(StartingPosition.TRIM_HORIZON);
        var rStartingTimestamp = runContext.render(startingTimestamp).as(Instant.class).orElse(null);
        var rSerdeType = runContext.render(serdeType).as(SerdeType.class).orElse(SerdeType.STRING);

        if (rStartingPosition == StartingPosition.AT_TIMESTAMP && rStartingTimestamp == null) {
            throw new IllegalArgumentException("'startingTimestamp' is required when 'startingPosition' is AT_TIMESTAMP");
        }

        var rMaxRecords = Consume.requireBoundedMaxRecords(runContext.render(maxRecords).as(Integer.class).orElse(null));
        var rMaxDuration = Consume.requireBoundedMaxDuration(runContext.render(maxDuration).as(Duration.class).orElse(null));
        var rMaxFetchBytes = Consume.requireBoundedMaxFetchBytes(runContext.render(maxFetchBytes).as(Integer.class).orElse(null));

        var client = client(runContext);
        var partitionIds = rPartitionId != null && !rPartitionId.isBlank()
            ? List.of(rPartitionId)
            : DisService.listPartitionIds(client, rStreamName);

        var kv = runContext.namespaceKv(triggerContext.getNamespace());
        var watermarkKey = DisWatermark.key(triggerContext);
        var watermark = DisWatermark.read(kv, watermarkKey);

        var config = new Consume.PollConfig(rStartingPosition, rStartingTimestamp, rSerdeType, rMaxRecords, rMaxDuration, rMaxFetchBytes);

        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        Consume.PollResult result;
        try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
            result = Consume.poll(runContext, client, rStreamName, partitionIds, watermark, config, output);
            output.flush();
        }

        // poll() already carries every current partition's prior watermark forward into
        // result.lastSequenceNumbers(), so persisting it directly keeps active partitions correct while
        // dropping entries for partitions that no longer exist (avoiding unbounded watermark-map growth).
        DisWatermark.write(kv, watermarkKey, result.lastSequenceNumbers());

        runContext.logger().info(
            "DIS trigger polled stream={} partitions={}: {} records", rStreamName, partitionIds.size(), result.count());

        if (result.count() == 0) {
            return Optional.empty();
        }

        var output = Consume.Output.builder()
            .count(result.count())
            .uri(runContext.storage().putFile(tempFile))
            .build();

        return Optional.of(TriggerService.generateExecution(this, conditionContext, triggerContext, output));
    }
}
