package io.kestra.plugin.huawei.dis;

import com.huaweicloud.sdk.dis.v2.DisClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.huawei.dis.models.Record;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Consume records from a Huawei Cloud DIS (Data Ingestion Service) stream",
    description = """
        Reads records from every partition of the configured stream (or a single `partitionId`, when
        set) until `maxRecords` or `maxDuration` is reached (at least one is required), or until the
        stream is fully caught up — whichever comes first. Records are written to Kestra internal
        storage as ION at `uri`.

        This task always starts reading from `startingPosition` (or `startingTimestamp`) — it does
        not remember where a previous execution left off. For continuous, non-overlapping consumption
        use `Trigger` or `RealtimeTrigger`, which persist a per-partition sequence-number watermark.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: dis_consume
                namespace: company.team

                tasks:
                  - id: consume
                    type: io.kestra.plugin.huawei.dis.Consume
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    streamName: my-stream
                    maxRecords: 500
                """
        )
    },
    metrics = {
        @Metric(name = "dis.consume.count", type = Counter.TYPE, unit = "records",
            description = "Number of records consumed from the DIS stream.")
    }
)
public class Consume extends AbstractDis implements RunnableTask<Consume.Output>, ConsumeOptionsInterface {

    // Hard caps so a mistyped or malicious value cannot force an unbounded loop or file.
    static final int MAX_RECORDS_HARD_CAP = 1_000_000;
    static final Duration MAX_DURATION_HARD_CAP = Duration.ofHours(24);
    // DIS's documented ceiling for a single consumeRecords call.
    static final int MAX_FETCH_BYTES_HARD_CAP = 2 * 1024 * 1024;

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
        title = "Stop after consuming this many records",
        description = "Upper bound across all partitions combined. Maximum " + MAX_RECORDS_HARD_CAP +
            ". At least one of `maxRecords` or `maxDuration` must be set."
    )
    @PluginProperty(group = "execution")
    private Property<Integer> maxRecords;

    @Schema(
        title = "Stop after this duration has elapsed",
        description = "ISO-8601 duration, e.g. `PT30S`. Maximum `PT24H`. At least one of `maxRecords` or " +
            "`maxDuration` must be set."
    )
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration;

    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Integer> maxFetchBytes = Property.ofValue(MAX_FETCH_BYTES_HARD_CAP);

    @Override
    public Output run(RunContext runContext) throws Exception {
        if (maxRecords == null && maxDuration == null) {
            throw new IllegalArgumentException("'maxRecords' or 'maxDuration' must be set to avoid an infinite loop");
        }

        var rStreamName = runContext.render(streamName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("'streamName' is required"));
        var rPartitionId = runContext.render(partitionId).as(String.class).orElse(null);
        var rStartingPosition = runContext.render(startingPosition).as(StartingPosition.class).orElse(StartingPosition.TRIM_HORIZON);
        var rStartingTimestamp = runContext.render(startingTimestamp).as(Instant.class).orElse(null);
        var rSerdeType = runContext.render(serdeType).as(SerdeType.class).orElse(SerdeType.STRING);
        var rMaxRecords = requireBoundedMaxRecords(runContext.render(maxRecords).as(Integer.class).orElse(null));
        var rMaxDuration = requireBoundedMaxDuration(runContext.render(maxDuration).as(Duration.class).orElse(null));
        var rMaxFetchBytes = requireBoundedMaxFetchBytes(runContext.render(maxFetchBytes).as(Integer.class).orElse(null));

        if (rStartingPosition == StartingPosition.AT_TIMESTAMP && rStartingTimestamp == null) {
            throw new IllegalArgumentException("'startingTimestamp' is required when 'startingPosition' is AT_TIMESTAMP");
        }

        var client = client(runContext);
        var partitionIds = rPartitionId != null && !rPartitionId.isBlank()
            ? List.of(rPartitionId)
            : DisService.listPartitionIds(client, rStreamName);

        var config = new PollConfig(rStartingPosition, rStartingTimestamp, rSerdeType, rMaxRecords, rMaxDuration, rMaxFetchBytes);

        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        PollResult result;
        try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
            result = poll(runContext, client, rStreamName, partitionIds, null, config, output);
            output.flush();
        }

        runContext.metric(Counter.of("dis.consume.count", result.count()));
        runContext.logger().debug("Consumed {} records from DIS stream {}", result.count(), rStreamName);

        return Output.builder()
            .count(result.count())
            .uri(runContext.storage().putFile(tempFile))
            .build();
    }

    /** Shared by {@code Consume}, {@code Trigger}, and {@code RealtimeTrigger} so the same bound applies everywhere. Returns {@code -1} when unset (no limit). */
    static int requireBoundedMaxRecords(Integer value) {
        if (value != null && value > MAX_RECORDS_HARD_CAP) {
            throw new IllegalArgumentException(
                "'maxRecords' (" + value + ") exceeds the maximum of " + MAX_RECORDS_HARD_CAP +
                " — lower the value or run multiple Consume tasks.");
        }
        return value != null ? value : -1;
    }

    /** Shared by {@code Consume} and {@code Trigger} so the same bound applies everywhere. */
    static Duration requireBoundedMaxDuration(Duration value) {
        if (value != null && value.compareTo(MAX_DURATION_HARD_CAP) > 0) {
            throw new IllegalArgumentException(
                "'maxDuration' (" + value + ") exceeds the maximum of " + MAX_DURATION_HARD_CAP + " — lower the value.");
        }
        return value;
    }

    /** Shared by {@code Consume}, {@code Trigger}, and {@code RealtimeTrigger} so the same bound applies everywhere. */
    static int requireBoundedMaxFetchBytes(Integer value) {
        var resolved = value != null ? value : MAX_FETCH_BYTES_HARD_CAP;
        if (resolved > MAX_FETCH_BYTES_HARD_CAP) {
            throw new IllegalArgumentException(
                "'maxFetchBytes' (" + resolved + ") exceeds DIS's per-request ceiling of " + MAX_FETCH_BYTES_HARD_CAP + " bytes (2 MB).");
        }
        return resolved;
    }

    /** Bundles the immutable per-call knobs {@link #poll} needs, keeping its signature manageable. */
    record PollConfig(StartingPosition startingPosition, Instant startingTimestamp, SerdeType serdeType,
                      int maxRecords, Duration maxDuration, int maxFetchBytes) {
    }

    /**
     * Round-robins {@code consumeRecords} across every partition until a stop condition is reached:
     * {@code maxRecords}/{@code maxDuration}, or a full round with zero records delivered (caught up).
     * Shared by {@link Consume#run} (fresh start every execution) and the triggers (resuming from a
     * persisted watermark via {@code resumeFrom}).
     */
    static PollResult poll(
        RunContext runContext, DisClient client, String rStreamName, List<String> partitionIds,
        Map<String, String> resumeFrom, PollConfig config, OutputStream out
    ) throws Exception {
        var logger = runContext.logger();
        var cursors = new LinkedHashMap<String, String>();
        var lastSequenceNumbers = new LinkedHashMap<String, String>();

        for (var pid : partitionIds) {
            var resumeSeq = resumeFrom != null ? resumeFrom.get(pid) : null;
            cursors.put(pid, DisService.cursorFor(client, rStreamName, pid, config.startingPosition(), config.startingTimestamp(), resumeSeq));
            if (resumeSeq != null) {
                lastSequenceNumbers.put(pid, resumeSeq);
            }
        }

        var total = 0;
        var started = Instant.now();
        var finished = false;

        while (!finished) {
            var roundRecords = 0;
            for (var pid : partitionIds) {
                var cursor = cursors.get(pid);
                if (cursor == null) {
                    continue;
                }

                var response = DisService.consumeWithCursorRefresh(
                    client, logger, rStreamName, pid, cursor, config.maxFetchBytes(),
                    config.startingPosition(), config.startingTimestamp(), lastSequenceNumbers.get(pid));

                var records = response.getRecords();
                if (records != null) {
                    for (var record : records) {
                        FileSerde.write(out, toRecord(pid, record, config.serdeType()));
                        lastSequenceNumbers.put(pid, record.getSequenceNumber());
                        total++;
                        roundRecords++;
                        if (config.maxRecords() >= 0 && total >= config.maxRecords()) {
                            break;
                        }
                    }
                }
                cursors.put(pid, response.getNextPartitionCursor());

                if (config.maxRecords() >= 0 && total >= config.maxRecords()) {
                    break;
                }
            }

            finished = (config.maxRecords() >= 0 && total >= config.maxRecords())
                || (config.maxDuration() != null && Instant.now().isAfter(started.plus(config.maxDuration())))
                || roundRecords == 0;
        }

        return new PollResult(total, lastSequenceNumbers);
    }

    static Record toRecord(String partitionId, com.huaweicloud.sdk.dis.v2.model.Record record, SerdeType serdeType) throws Exception {
        var raw = record.getData() != null ? Base64.getDecoder().decode(record.getData()) : null;
        return Record.builder()
            .partitionId(partitionId)
            .sequenceNumber(record.getSequenceNumber())
            .partitionKey(record.getPartitionKey())
            .data(serdeType.deserialize(raw))
            .timestamp(record.getTimestamp() != null ? Instant.ofEpochMilli(record.getTimestamp()) : null)
            .build();
    }

    record PollResult(int count, Map<String, String> lastSequenceNumbers) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Number of records consumed from the DIS stream")
        private final Integer count;

        @Schema(title = "URI of the ION file in Kestra internal storage containing the consumed records")
        private final URI uri;
    }
}
