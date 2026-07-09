package io.kestra.plugin.huawei.dli;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.dli.v1.DliClient;
import com.huaweicloud.sdk.dli.v1.model.CancelSqlJobRequest;
import com.huaweicloud.sdk.dli.v1.model.CreateSqlJobRequest;
import com.huaweicloud.sdk.dli.v1.model.CreateSqlJobRequestBody;
import com.huaweicloud.sdk.dli.v1.model.CreateSqlJobResponse;
import com.huaweicloud.sdk.dli.v1.model.ExportSqlJobResultRequest;
import com.huaweicloud.sdk.dli.v1.model.ExportSqlJobResultRequestBody;
import com.huaweicloud.sdk.dli.v1.model.ExportSqlJobResultResponse;
import com.huaweicloud.sdk.dli.v1.model.PreviewSqlJobResultRequest;
import com.huaweicloud.sdk.dli.v1.model.PreviewSqlJobResultResponse;
import com.huaweicloud.sdk.dli.v1.model.ShowSqlJobStatusRequest;
import com.huaweicloud.sdk.dli.v1.model.ShowSqlJobStatusResponse;
import com.huaweicloud.sdk.dli.v1.model.Tag;
import com.obs.services.ObsClient;
import com.obs.services.model.GetObjectRequest;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.huawei.obs.ObsService;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Reusable DLI SQL-job operations shared by {@link Query}: submit, poll, preview, and export+read-back.
 * Kept static and RunContext-aware only where storage I/O is needed, mirroring {@code ObsService}.
 */
final class DliService {

    private DliService() {
    }

    static CreateSqlJobResponse submitJob(
        DliClient client,
        String sql,
        String database,
        String queue,
        List<String> conf,
        Map<String, String> tags
    ) {
        var body = new CreateSqlJobRequestBody().withSql(sql);
        if (database != null && !database.isBlank()) {
            body.withCurrentdb(database);
        }
        if (queue != null && !queue.isBlank()) {
            body.withQueueName(queue);
        }
        if (conf != null && !conf.isEmpty()) {
            body.withConf(conf);
        }
        if (tags != null && !tags.isEmpty()) {
            body.withTags(tags.entrySet().stream()
                .map(e -> new Tag().withKey(e.getKey()).withValue(e.getValue()))
                .toList());
        }

        try {
            return client.createSqlJob(new CreateSqlJobRequest().withBody(body));
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "DLI job submission failed (HTTP " + e.getHttpStatusCode() + "): " + e.getErrorMsg() +
                " — verify the SQL syntax, the queue name, and that the AK/SK has DLI permissions.", e);
        } catch (SdkException e) {
            throw new IllegalStateException("DLI SDK error submitting job: " + e.getMessage(), e);
        }
    }

    static ShowSqlJobStatusResponse pollUntilTerminal(
        DliClient client,
        String jobId,
        Duration interval,
        Duration maxDuration,
        Logger logger
    ) throws InterruptedException {
        var deadline = System.currentTimeMillis() + maxDuration.toMillis();
        var current = showStatus(client, jobId);

        while (!isTerminal(current.getStatus())) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "DLI job '" + jobId + "' did not reach a terminal state within " + maxDuration +
                    " — current status: " + current.getStatus() +
                    ". The job keeps running on DLI and is not automatically cancelled; " +
                    "increase 'maxDuration' or cancel it manually if it is stuck.");
            }
            Thread.sleep(interval.toMillis());
            current = showStatus(client, jobId);
            logger.debug("DLI job '{}' status={}", jobId, current.getStatus());
        }

        if (current.getStatus() == ShowSqlJobStatusResponse.StatusEnum.FAILED
            || current.getStatus() == ShowSqlJobStatusResponse.StatusEnum.CANCELLED) {
            throw new IllegalStateException(
                "DLI job '" + jobId + "' finished with status '" + current.getStatus() + "'" +
                (current.getMessage() != null ? ": " + current.getMessage() : "") +
                (current.getDetail() != null ? " (" + current.getDetail() + ")" : "") +
                " — check the DLI console job history for details.");
        }

        return current;
    }

    static boolean isTerminal(ShowSqlJobStatusResponse.StatusEnum status) {
        return status == ShowSqlJobStatusResponse.StatusEnum.FINISHED
            || status == ShowSqlJobStatusResponse.StatusEnum.FAILED
            || status == ShowSqlJobStatusResponse.StatusEnum.CANCELLED;
    }

    private static ShowSqlJobStatusResponse showStatus(DliClient client, String jobId) {
        try {
            return client.showSqlJobStatus(new ShowSqlJobStatusRequest().withJobId(jobId));
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "DLI job status check failed for job '" + jobId + "' (HTTP " + e.getHttpStatusCode() + "): " +
                e.getErrorMsg(), e);
        } catch (SdkException e) {
            throw new IllegalStateException(
                "DLI SDK error checking status of job '" + jobId + "': " + e.getMessage(), e);
        }
    }

    static PreviewSqlJobResultResponse preview(DliClient client, String jobId, String queue) {
        var request = new PreviewSqlJobResultRequest().withJobId(jobId);
        if (queue != null && !queue.isBlank()) {
            request.withQueueName(queue);
        }
        try {
            return client.previewSqlJobResult(request);
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "DLI result preview failed for job '" + jobId + "' (HTTP " + e.getHttpStatusCode() + "): " +
                e.getErrorMsg(), e);
        } catch (SdkException e) {
            throw new IllegalStateException(
                "DLI SDK error previewing result of job '" + jobId + "': " + e.getMessage(), e);
        }
    }

    /**
     * Submits an export-result job that writes the full result set of {@code jobId} to OBS as
     * newline-delimited JSON under {@code dataPath} (an {@code obs://bucket/prefix} URI). The
     * returned response carries the export job's own {@code jobId}, which must be polled separately
     * via {@link #pollUntilTerminal}.
     */
    static ExportSqlJobResultResponse submitExport(DliClient client, String jobId, String dataPath, String queue) {
        var body = new ExportSqlJobResultRequestBody()
            .withDataPath(dataPath)
            .withDataType("json")
            .withExportMode(ExportSqlJobResultRequestBody.ExportModeEnum.OVERWRITE);
        if (queue != null && !queue.isBlank()) {
            body.withQueueName(queue);
        }
        try {
            return client.exportSqlJobResult(new ExportSqlJobResultRequest().withJobId(jobId).withBody(body));
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "DLI result export failed for job '" + jobId + "' (HTTP " + e.getHttpStatusCode() + "): " +
                e.getErrorMsg(), e);
        } catch (SdkException e) {
            throw new IllegalStateException(
                "DLI SDK error exporting result of job '" + jobId + "': " + e.getMessage(), e);
        }
    }

    /** Best-effort cancellation; never throws — used both on timeout and from {@code kill()}. */
    static void cancelQuietly(DliClient client, String jobId, Logger logger) {
        try {
            client.cancelSqlJob(new CancelSqlJobRequest().withJobId(jobId));
        } catch (Exception e) {
            logger.warn("Failed to cancel DLI job '{}': {}", jobId, e.getMessage());
        }
    }

    /**
     * Downloads every object exported under {@code bucket}/{@code prefix} (the export job may split
     * the result into multiple part files), parses each line as a JSON row, and re-serializes all rows
     * as ION into Kestra internal storage. Returns the row count written.
     */
    static long readExportedRowsToIon(
        ObsClient obs,
        String bucket,
        String prefix,
        OutputStream ionOutput
    ) throws Exception {
        var count = new long[]{0};
        ObsService.list(obs, bucket, prefix, null, null, 1000, null, obj -> {
            var req = new GetObjectRequest(bucket, obj.getKey());
            try (
                var body = obs.getObject(req).getObjectContent();
                var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))
            ) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    var row = JacksonMapper.ofJson().readValue(line, Map.class);
                    FileSerde.write(ionOutput, row);
                    count[0]++;
                }
            }
        });
        return count[0];
    }

    /** Splits an {@code obs://bucket/key/prefix} URI into its bucket and key-prefix parts. */
    static String[] parseObsUri(String uri) {
        if (uri == null || !uri.startsWith("obs://")) {
            throw new IllegalArgumentException(
                "'outputLocation' must be an OBS URI in the form `obs://bucket/prefix`, but was: " + uri);
        }
        var withoutScheme = uri.substring("obs://".length());
        var slash = withoutScheme.indexOf('/');
        if (slash < 0) {
            return new String[]{withoutScheme, ""};
        }
        return new String[]{withoutScheme.substring(0, slash), withoutScheme.substring(slash + 1)};
    }
}
