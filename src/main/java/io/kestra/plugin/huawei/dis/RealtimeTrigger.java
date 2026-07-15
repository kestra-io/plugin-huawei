package io.kestra.plugin.huawei.dis;

import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.dis.v2.model.ConsumeRecordsRequest;
import com.huaweicloud.sdk.dis.v2.model.ConsumeRecordsResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.RealtimeTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.plugin.huawei.dis.models.Record;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.reactivestreams.Publisher;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow for each record on a Huawei Cloud DIS (Data Ingestion Service) stream",
    description = """
        Maintains a persistent poll loop across every partition of the configured stream (or a single
        `partitionId`, when set) and fires one Kestra execution per record as records arrive. A
        per-partition sequence-number watermark is persisted in the flow's namespace
        [KV Store](https://kestra.io/docs/concepts/kv-store) after each non-empty batch, so restarting
        the trigger resumes close to where it left off instead of re-reading the whole stream.
        For batched interval-based consumption use `Trigger`.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: dis_realtime_trigger
                namespace: company.team

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.data }}"

                triggers:
                  - id: realtime
                    type: io.kestra.plugin.huawei.dis.RealtimeTrigger
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    streamName: my-stream
                """
        )
    }
)
public class RealtimeTrigger extends AbstractDisTrigger
    implements RealtimeTriggerInterface, TriggerOutput<Record> {

    // Delay between rounds when a full round across all partitions returned zero records, to avoid busy-spinning DIS.
    private static final Duration IDLE_POLL_DELAY = Duration.ofSeconds(2);

    @Schema(title = "DIS stream name")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> streamName;

    @Schema(
        title = "Single partition to consume from",
        description = "When omitted (default), every partition of the stream is consumed."
    )
    @PluginProperty(group = "main")
    private Property<String> partitionId;

    @Schema(
        title = "Where to start reading a partition the first time it has no watermark yet",
        description = "`TRIM_HORIZON` (default) reads from the oldest retained record. `LATEST` reads only new " +
            "records. `AT_TIMESTAMP` reads from the oldest record at or after `startingTimestamp` (required in that case)."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<StartingPosition> startingPosition = Property.ofValue(StartingPosition.TRIM_HORIZON);

    @Schema(title = "Starting timestamp", description = "Required when `startingPosition` is `AT_TIMESTAMP`.")
    @PluginProperty(group = "main")
    private Property<Instant> startingTimestamp;

    @Schema(title = "Deserialization type applied to each record's `data` value")
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<SerdeType> serdeType = Property.ofValue(SerdeType.STRING);

    @Schema(
        title = "Maximum bytes fetched per partition, per call",
        description = "Defaults to 2 MB (2097152 bytes), DIS's per-request payload ceiling."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Integer> maxFetchBytes = Property.ofValue(Consume.MAX_FETCH_BYTES_HARD_CAP);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    @Builder.Default
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final CountDownLatch waitForTermination = new CountDownLatch(1);

    @Override
    public Publisher<Execution> evaluate(ConditionContext conditionContext, TriggerContext triggerContext) throws Exception {
        var runContext = conditionContext.getRunContext();

        var rStreamName = runContext.render(streamName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("'streamName' is required"));
        var rPartitionId = runContext.render(partitionId).as(String.class).orElse(null);
        var rStartingPosition = runContext.render(startingPosition).as(StartingPosition.class).orElse(StartingPosition.TRIM_HORIZON);
        var rStartingTimestamp = runContext.render(startingTimestamp).as(Instant.class).orElse(null);
        var rSerdeType = runContext.render(serdeType).as(SerdeType.class).orElse(SerdeType.STRING);
        var rMaxFetchBytes = Consume.requireBoundedMaxFetchBytes(runContext.render(maxFetchBytes).as(Integer.class).orElse(null));

        if (rStartingPosition == StartingPosition.AT_TIMESTAMP && rStartingTimestamp == null) {
            throw new IllegalArgumentException("'startingTimestamp' is required when 'startingPosition' is AT_TIMESTAMP");
        }

        return Flux.create(sink -> {
            var logger = runContext.logger();
            try {
                logger.info("Starting DIS realtime trigger triggerId={} stream={}", this.id, rStreamName);

                var client = client(runContext);
                var partitionIds = rPartitionId != null && !rPartitionId.isBlank()
                    ? List.of(rPartitionId)
                    : DisService.listPartitionIds(client, rStreamName);

                var kv = runContext.namespaceKv(triggerContext.getNamespace());
                var watermarkKey = DisWatermark.key(triggerContext);
                var watermark = DisWatermark.read(kv, watermarkKey);

                var cursors = new LinkedHashMap<String, String>();
                var lastSequenceNumbers = new LinkedHashMap<String, String>(watermark != null ? watermark : Map.<String, String>of());
                for (var pid : partitionIds) {
                    cursors.put(pid, DisService.cursorFor(
                        client, rStreamName, pid, rStartingPosition, rStartingTimestamp, lastSequenceNumbers.get(pid)));
                }

                logger.info("DIS realtime trigger subscribed triggerId={} stream={} partitions={}", this.id, rStreamName, partitionIds.size());

                while (isActive.get()) {
                    var roundRecords = 0;
                    for (var pid : partitionIds) {
                        if (!isActive.get()) {
                            break;
                        }
                        var cursor = cursors.get(pid);
                        if (cursor == null) {
                            continue;
                        }

                        var request = new ConsumeRecordsRequest().withPartitionCursor(cursor).withMaxFetchBytes(rMaxFetchBytes);
                        ConsumeRecordsResponse response;
                        try {
                            response = client.consumeRecords(request);
                        } catch (ServiceResponseException e) {
                            logger.debug("DIS partition cursor for partition '{}' was rejected ({}); requesting a fresh cursor", pid, e.getMessage());
                            var refreshed = DisService.cursorFor(
                                client, rStreamName, pid, rStartingPosition, rStartingTimestamp, lastSequenceNumbers.get(pid));
                            response = client.consumeRecords(new ConsumeRecordsRequest().withPartitionCursor(refreshed).withMaxFetchBytes(rMaxFetchBytes));
                        }

                        var records = response.getRecords();
                        if (records != null) {
                            for (var record : records) {
                                var decoded = Consume.toRecord(pid, record, rSerdeType);
                                sink.next(TriggerService.generateRealtimeExecution(this, conditionContext, triggerContext, decoded));
                                lastSequenceNumbers.put(pid, record.getSequenceNumber());
                                roundRecords++;
                            }
                        }
                        cursors.put(pid, response.getNextPartitionCursor());
                    }

                    if (roundRecords > 0) {
                        DisWatermark.write(kv, watermarkKey, lastSequenceNumbers);
                    } else if (isActive.get()) {
                        Thread.sleep(IDLE_POLL_DELAY.toMillis());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("DIS realtime trigger triggerId={} stream={} failed: {}", this.id, rStreamName, e.getMessage());
                sink.error(e);
            } finally {
                sink.complete();
                waitForTermination.countDown();
            }
        });
    }

    @Override
    public void kill() {
        stop(true);
    }

    @Override
    public void stop() {
        stop(false);
    }

    private void stop(boolean wait) {
        if (!isActive.compareAndSet(true, false)) {
            return;
        }
        LoggerFactory.getLogger(RealtimeTrigger.class).debug("Stopping DIS realtime trigger triggerId={} (wait={})", this.id, wait);
        if (wait) {
            try {
                waitForTermination.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
