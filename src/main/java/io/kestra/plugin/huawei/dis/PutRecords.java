package io.kestra.plugin.huawei.dis;

import com.huaweicloud.sdk.dis.v2.DisClient;
import com.huaweicloud.sdk.dis.v2.model.PutRecordsRequest;
import com.huaweicloud.sdk.dis.v2.model.PutRecordsRequestEntry;
import com.huaweicloud.sdk.dis.v2.model.SendRecordsRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Data;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Write a batch of records to a Huawei Cloud DIS (Data Ingestion Service) stream",
    description = """
        Sends one or more records to a DIS stream — the Huawei Cloud equivalent of
        `io.kestra.plugin.aws.kinesis.PutRecords`. Each record requires `data` and either
        `partitionKey` (routes by hash) or `partitionId` (targets a specific partition directly);
        `explicitHashKey` optionally overrides the hash computed from `partitionKey`.

        Records are chunked automatically to respect DIS's per-request limits (500 records and
        5 MB per request); a single record larger than 1 MB fails the task naming the offending
        record. DIS can reject individual records within an otherwise-successful (HTTP 200) batch —
        `failedRecordCount` and the `uri` output surface exactly which ones failed and why.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Send records defined inline as maps.",
            full = true,
            code = """
                id: dis_put_records
                namespace: company.team

                tasks:
                  - id: put_records
                    type: io.kestra.plugin.huawei.dis.PutRecords
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    streamName: my-stream
                    from:
                      - data: "user sign-in event"
                        partitionKey: "user-1"
                      - data: "user sign-out event"
                        partitionKey: "user-1"
                """
        ),
        @Example(
            title = "Send JSON records read from an internal storage ION file.",
            full = true,
            code = """
                id: dis_put_records_json
                namespace: company.team

                inputs:
                  - id: dataFile
                    type: FILE

                tasks:
                  - id: put_records
                    type: io.kestra.plugin.huawei.dis.PutRecords
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    streamName: my-stream
                    serdeType: JSON
                    from: "{{ inputs.dataFile }}"
                """
        )
    },
    metrics = {
        @Metric(name = "dis.putrecords.count", type = Counter.TYPE, unit = "records",
            description = "Number of records attempted to be sent to the DIS stream."),
        @Metric(name = "dis.putrecords.failed", type = Counter.TYPE, unit = "records",
            description = "Number of records that DIS rejected within the batch.")
    }
)
public class PutRecords extends AbstractDis implements RunnableTask<PutRecords.Output>, Data.From {

    // DIS's per-request limits: https://support.huaweicloud.com/intl/en-us/api-dis/dis_02_0018.html
    static final int MAX_RECORDS_PER_BATCH = 500;
    static final long MAX_BATCH_BYTES = 5L * 1024 * 1024;
    static final long MAX_RECORD_BYTES = 1024 * 1024;

    @Schema(title = "DIS stream name")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> streamName;

    @Schema(
        title = "Records to send",
        description = """
            A single map, a list of maps, or a URI pointing to an ION file in Kestra internal storage.
            Each map requires `data` and either `partitionKey` (routes the record to a partition by
            hash) or `partitionId` (targets a specific partition directly); `explicitHashKey` may
            optionally override the hash computed from `partitionKey`.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Object from;

    @Schema(
        title = "Serialization type applied to each record's `data` value",
        description = "`STRING` (default) encodes text as UTF-8; `JSON` serializes a map to a JSON string; " +
            "`BINARY` expects the value to already be a byte array. All three are base64-encoded on the wire."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<SerdeType> serdeType = Property.ofValue(SerdeType.STRING);

    @Schema(
        title = "Fail the task if any record is rejected",
        description = "If `true` (default), the task fails when DIS rejects at least one record in the batch. " +
            "Set to `false` to continue despite partial failures — inspect `failedRecordCount` and `uri` instead."
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Boolean> failOnUnsuccessfulRecords = Property.ofValue(true);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rStreamName = runContext.render(streamName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("'streamName' is required"));
        var rSerdeType = runContext.render(serdeType).as(SerdeType.class).orElse(SerdeType.STRING);
        var rFailOnUnsuccessful = runContext.render(failOnUnsuccessfulRecords).as(Boolean.class).orElse(true);

        var client = client(runContext);

        var recordCount = 0;
        var failedCount = 0;
        var index = 0;

        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
            var batch = new ArrayList<PutRecordsRequestEntry>();
            var batchBytes = 0L;

            for (var map : Data.from(from).read(runContext).toIterable()) {
                var rawData = map.get("data");
                if (rawData == null) {
                    throw new IllegalArgumentException("Record at index " + index + " is missing the required 'data' field");
                }
                var partitionKey = map.get("partitionKey") != null ? String.valueOf(map.get("partitionKey")) : null;
                var partitionId = map.get("partitionId") != null ? String.valueOf(map.get("partitionId")) : null;
                if (partitionKey == null && partitionId == null) {
                    throw new IllegalArgumentException(
                        "Record at index " + index + " must set either 'partitionKey' or 'partitionId'");
                }

                var bytes = rSerdeType.serialize(rawData);
                if (bytes != null && bytes.length > MAX_RECORD_BYTES) {
                    throw new IllegalArgumentException(
                        "Record at index " + index + " (partitionKey=" + partitionKey + ") is " + bytes.length +
                        " bytes, exceeding DIS's " + MAX_RECORD_BYTES + " byte (1 MB) per-record limit — split the payload before sending.");
                }

                var entry = new PutRecordsRequestEntry()
                    .withData(bytes != null ? Base64.getEncoder().encodeToString(bytes) : null)
                    .withPartitionKey(partitionKey)
                    .withPartitionId(partitionId);
                if (map.get("explicitHashKey") != null) {
                    entry.withExplicitHashKey(String.valueOf(map.get("explicitHashKey")));
                }

                var entryBytes = estimateBytes(entry);
                if (!batch.isEmpty() && (batch.size() >= MAX_RECORDS_PER_BATCH || batchBytes + entryBytes > MAX_BATCH_BYTES)) {
                    failedCount += sendBatch(client, rStreamName, batch, output);
                    batch = new ArrayList<>();
                    batchBytes = 0L;
                }
                batch.add(entry);
                batchBytes += entryBytes;
                recordCount++;
                index++;
            }

            if (recordCount == 0) {
                throw new IllegalArgumentException("'from' produced no records to send to DIS stream '" + rStreamName + "'");
            }

            if (!batch.isEmpty()) {
                failedCount += sendBatch(client, rStreamName, batch, output);
            }

            output.flush();
        }

        runContext.metric(Counter.of("dis.putrecords.count", recordCount));
        runContext.metric(Counter.of("dis.putrecords.failed", failedCount));

        var uri = runContext.storage().putFile(tempFile);

        if (rFailOnUnsuccessful && failedCount > 0) {
            throw new IllegalStateException(
                failedCount + " of " + recordCount + " record(s) were rejected by DIS stream '" + rStreamName +
                "' — inspect the 'uri' output for the per-record error codes, or set 'failOnUnsuccessfulRecords' " +
                "to false to continue despite partial failures.");
        }

        return Output.builder()
            .recordCount(recordCount)
            .failedRecordCount(failedCount)
            .uri(uri)
            .build();
    }

    /** Sends one batch and streams its per-record results straight to the ION output, so callers never hold a full-batch results list in memory. */
    private static int sendBatch(DisClient client, String streamName, List<PutRecordsRequestEntry> batch, OutputStream out) throws Exception {
        var response = client.sendRecords(new SendRecordsRequest().withBody(
            new PutRecordsRequest().withStreamName(streamName).withRecords(batch)));
        if (response.getRecords() != null) {
            for (var result : response.getRecords()) {
                FileSerde.write(out, Result.builder()
                    .partitionId(result.getPartitionId())
                    .sequenceNumber(result.getSequenceNumber())
                    .errorCode(result.getErrorCode())
                    .errorMessage(result.getErrorMessage())
                    .build());
            }
        }
        return response.getFailedRecordCount() != null ? response.getFailedRecordCount() : 0;
    }

    // Conservative upper bound: uses the base64-ENCODED data length (~33% larger than raw, which is
    // what travels on the wire) plus the routing-key fields, so a batch never under-counts against
    // DIS's 5 MB per-request limit. Over-counting only costs an extra request, never a rejected batch.
    private static long estimateBytes(PutRecordsRequestEntry entry) {
        long size = entry.getData() != null ? entry.getData().length() : 0;
        size += entry.getPartitionKey() != null ? entry.getPartitionKey().length() : 0;
        size += entry.getPartitionId() != null ? entry.getPartitionId().length() : 0;
        size += entry.getExplicitHashKey() != null ? entry.getExplicitHashKey().length() : 0;
        return size;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Number of records sent to the DIS stream")
        private final Integer recordCount;

        @Schema(title = "Number of records that DIS rejected within the batch")
        private final Integer failedRecordCount;

        @Schema(title = "URI of the ION file in Kestra internal storage containing per-record results")
        private final URI uri;
    }

    @Builder
    @Getter
    public static class Result implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Id of the partition the record was written to (or that rejected it)")
        private final String partitionId;

        @Schema(title = "Sequence number assigned to the record", description = "`null` when the record was rejected.")
        private final String sequenceNumber;

        @Schema(title = "DIS error code", description = "`null` when the record was written successfully.")
        private final String errorCode;

        @Schema(title = "DIS error message", description = "`null` when the record was written successfully.")
        private final String errorMessage;
    }
}
