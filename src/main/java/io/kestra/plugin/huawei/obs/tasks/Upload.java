package io.kestra.plugin.huawei.obs.tasks;

import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.PutObjectRequest;
import com.obs.services.model.StorageClassEnum;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.obs.AbstractObsObject;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Upload a file from Kestra internal storage to Huawei OBS.",
    description = """
        Reads a file from Kestra internal storage (identified by a `kestra://` URI) and uploads it to
        the specified OBS bucket. Content length is always set explicitly so the upload works against both
        real OBS and S3-compatible endpoints (which require `Content-Length` for streaming uploads).
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: obs_upload
                namespace: company.team

                tasks:
                  - id: upload
                    type: io.kestra.plugin.huawei.obs.tasks.Upload
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-bucket"
                    from: "{{ inputs.file }}"
                    key: "uploads/data.csv"
                    contentType: "text/csv"
                """
        )
    },
    metrics = {
        @Metric(name = "file.size", type = Counter.TYPE)
    }
)
public class Upload extends AbstractObsObject implements RunnableTask<Upload.Output> {

    @Schema(
        title = "Kestra internal storage URI of the file to upload.",
        description = "The URI of a file stored in Kestra internal storage (e.g. from a previous task output). " +
            "Must be a `kestra://` URI."
    )
    @NotNull
    @PluginProperty(group = "source", internalStorageURI = true)
    private Property<String> from;

    @Schema(
        title = "OBS object key (path within the bucket).",
        description = "The key under which the object will be stored in the bucket, e.g. `data/2024/file.csv`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> key;

    @Schema(
        title = "MIME content type of the uploaded object.",
        description = "Set to the appropriate MIME type (e.g. `text/csv`, `application/json`). " +
            "OBS uses this value when serving the object."
    )
    @PluginProperty(group = "main")
    private Property<String> contentType;

    @Schema(
        title = "User-defined metadata to attach to the object.",
        description = """
            Key/value pairs stored as object metadata. Keys must be bare names without any prefix —
            the OBS SDK prepends `x-obs-meta-` automatically. Values are stored as ASCII strings; any
            non-string value (number, boolean) is converted via its string form.
            Example: `{ "author": "kestra", "env": "prod" }`.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, Object>> metadata;

    @Schema(
        title = "OBS storage class for the uploaded object.",
        description = """
            Controls the storage tier:
            - `STANDARD` — frequently accessed data (default when unset).
            - `WARM` — infrequently accessed data; lower storage cost, retrieval fee applies.
            - `COLD` — archival data; lowest cost, higher retrieval latency.
            - `DEEP_ARCHIVE` — long-term archival.
            - `INTELLIGENT_TIERING` — automatic tier transitions based on access patterns.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<StorageClassEnum> storageClass;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(bucket).as(String.class).orElseThrow();
        var rKey = runContext.render(key).as(String.class).orElseThrow();
        var rFrom = runContext.render(from).as(String.class).orElseThrow();
        var rContentType = runContext.render(contentType).as(String.class).orElse(null);
        var rMetadata = runContext.render(metadata).asMap(String.class, Object.class);
        var rStorageClass = runContext.render(storageClass).as(StorageClassEnum.class).orElse(null);

        var fileUri = URI.create(rFrom);
        var fileSize = runContext.storage().getAttributes(fileUri).getSize();

        runContext.logger().debug("Uploading to OBS s3://{}/{} ({} bytes)", rBucket, rKey, fileSize);

        try (var obs = client(runContext)) {
            var meta = new ObjectMetadata();
            // Content-Length is mandatory for stream uploads — OBS and MinIO both return HTTP 411 without it
            meta.setContentLength(fileSize);
            if (rContentType != null) {
                meta.setContentType(rContentType);
            }
            rMetadata.forEach((k, v) -> meta.addUserMetadata(k, String.valueOf(v)));
            if (rStorageClass != null) {
                meta.setObjectStorageClass(rStorageClass);
            }

            try (InputStream in = runContext.storage().getFile(fileUri)) {
                var req = new PutObjectRequest(rBucket, rKey, in);
                req.setMetadata(meta);
                var result = obs.putObject(req);
                runContext.logger().debug("Upload complete, ETag={}", result.getEtag());
                runContext.metric(Counter.of("file.size", fileSize));
                return Output.builder()
                    .bucket(rBucket)
                    .key(rKey)
                    .eTag(result.getEtag())
                    .versionId(result.getVersionId())
                    .build();
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Bucket the object was uploaded to.")
        private final String bucket;

        @Schema(title = "Key of the uploaded object.")
        private final String key;

        @Schema(title = "ETag assigned by OBS after a successful upload.")
        private final String eTag;

        @Schema(
            title = "Version ID of the uploaded object.",
            description = "Non-null only when bucket versioning is enabled."
        )
        private final String versionId;
    }
}
