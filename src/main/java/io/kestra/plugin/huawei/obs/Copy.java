package io.kestra.plugin.huawei.obs;

import com.obs.services.ObsClient;
import com.obs.services.exception.ObsException;
import com.obs.services.model.CopyObjectRequest;
import com.obs.services.model.DeleteObjectRequest;
import com.obs.services.model.ObjectMetadata;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.obs.AbstractObs;
import io.kestra.plugin.huawei.obs.ObsService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Copy an object within or between Huawei OBS buckets.",
    description = """
        Copies an OBS object from a source bucket/key to a destination bucket/key using a server-side
        copy — no data is transferred through Kestra. Set `delete` to `true` to implement move semantics
        (copy then delete the source object).
        """
)
@Plugin(
    aliases = "io.kestra.plugin.huawei.obs.tasks.Copy",
    examples = {
        @Example(
            full = true,
            code = """
                id: obs_copy
                namespace: company.team

                tasks:
                  - id: copy
                    type: io.kestra.plugin.huawei.obs.Copy
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    from:
                      bucket: "my-source-bucket"
                      key: "raw/2024/data.csv"
                    to:
                      bucket: "my-dest-bucket"
                      key: "processed/2024/data.csv"
                """
        ),
        @Example(
            title = "Move (copy then delete source)",
            full = true,
            code = """
                id: obs_move
                namespace: company.team

                tasks:
                  - id: move
                    type: io.kestra.plugin.huawei.obs.Copy
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    from:
                      bucket: "my-bucket"
                      key: "inbox/file.csv"
                    to:
                      bucket: "my-bucket"
                      key: "archive/file.csv"
                    delete: true
                """
        )
    }
)
public class Copy extends AbstractObs implements RunnableTask<Copy.Output> {

    @Schema(title = "Source object location.")
    @NotNull
    @PluginProperty(group = "source")
    private CopyObjectFrom from;

    @Schema(title = "Destination object location.")
    @NotNull
    @PluginProperty(group = "destination")
    private CopyObjectTo to;

    @Schema(
        title = "Delete the source object after a successful copy.",
        description = "When `true`, the task behaves like a move: it copies the object server-side and then " +
            "issues a delete on the source key. The delete only happens if the copy succeeds."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Boolean> delete = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rDelete = runContext.render(delete).as(Boolean.class).orElse(false);

        var rFromBucket = runContext.render(from.bucket).as(String.class).orElseThrow();
        var rFromKey = runContext.render(from.key).as(String.class).orElseThrow();
        var rFromVersionId = runContext.render(from.versionId).as(String.class).orElse(null);
        var rToBucket = runContext.render(to.bucket).as(String.class).orElseThrow();
        var rToKey = runContext.render(to.key).as(String.class).orElseThrow();

        runContext.logger().debug(
            "Copying OBS object obs://{}/{} → obs://{}/{} (delete={})",
            rFromBucket, rFromKey, rToBucket, rToKey, rDelete
        );

        try (var obs = client(runContext)) {
            var req = new CopyObjectRequest(rFromBucket, rFromKey, rToBucket, rToKey);
            if (rFromVersionId != null && !rFromVersionId.isBlank()) {
                req.setVersionId(rFromVersionId);
            }

            // Capture the source metadata before the copy so we can verify the copy landed before
            // deleting the source (move semantics) — a silently-incomplete copy must never lose data.
            var sourceMeta = rDelete ? sourceMetadata(obs, rFromBucket, rFromKey, rFromVersionId) : null;

            var result = obs.copyObject(req);

            if (rDelete) {
                ObsService.verifyServerSideCopy(
                    obs, rToBucket, rToKey,
                    sourceMeta != null ? sourceMeta.getEtag() : null,
                    sourceMeta != null ? sourceMeta.getContentLength() : null,
                    result.getEtag()
                );

                runContext.logger().debug("Deleting source object obs://{}/{}", rFromBucket, rFromKey);
                var delReq = new DeleteObjectRequest(rFromBucket, rFromKey);
                if (rFromVersionId != null && !rFromVersionId.isBlank()) {
                    delReq.setVersionId(rFromVersionId);
                }
                obs.deleteObject(delReq);
            }

            return Output.builder()
                .bucket(rToBucket)
                .key(rToKey)
                .eTag(result.getEtag())
                .versionId(result.getVersionId())
                .build();
        }
    }

    /**
     * Reads the source object's metadata (version-aware) so a move can verify the copy before deleting
     * the source. Returns {@code null} if the source cannot be read, in which case verification falls
     * back to a destination existence check.
     */
    private static ObjectMetadata sourceMetadata(ObsClient obs, String bucket, String key, String versionId) {
        try {
            return versionId != null && !versionId.isBlank()
                ? obs.getObjectMetadata(bucket, key, versionId)
                : obs.getObjectMetadata(bucket, key);
        } catch (ObsException e) {
            return null;
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Destination bucket.")
        private final String bucket;

        @Schema(title = "Destination object key.")
        private final String key;

        @Schema(title = "ETag of the destination object after the copy.")
        private final String eTag;

        @Schema(
            title = "Version ID of the destination object.",
            description = "Non-null only when bucket versioning is enabled."
        )
        private final String versionId;
    }

    @Builder
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CopyObjectFrom {

        @Schema(title = "Source bucket name.")
        @NotNull
        @PluginProperty(group = "source")
        private Property<String> bucket;

        @Schema(title = "Source object key.")
        @NotNull
        @PluginProperty(group = "source")
        private Property<String> key;

        @Schema(
            title = "Version ID of the source object.",
            description = "When set, copies the specified version. Only applicable when versioning is enabled."
        )
        @PluginProperty(group = "source")
        private Property<String> versionId;
    }

    @Builder
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CopyObjectTo {

        @Schema(title = "Destination bucket name.")
        @NotNull
        @PluginProperty(group = "destination")
        private Property<String> bucket;

        @Schema(title = "Destination object key.")
        @NotNull
        @PluginProperty(group = "destination")
        private Property<String> key;
    }
}
