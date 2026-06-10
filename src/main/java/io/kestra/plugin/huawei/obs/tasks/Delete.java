package io.kestra.plugin.huawei.obs.tasks;

import com.obs.services.model.DeleteObjectRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
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

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Delete an object from a Huawei OBS bucket.",
    description = """
        Deletes the specified object from an OBS bucket. When versioning is enabled, pass `versionId` to
        delete a specific version; without `versionId`, OBS inserts a delete marker (the object becomes
        invisible but is not permanently removed). On non-versioned buckets and on most S3-compatible
        endpoints such as MinIO, the object is permanently deleted regardless.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: obs_delete
                namespace: company.team

                tasks:
                  - id: delete
                    type: io.kestra.plugin.huawei.obs.tasks.Delete
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-bucket"
                    key: "tmp/stale-file.csv"
                """
        )
    }
)
public class Delete extends AbstractObsObject implements RunnableTask<Delete.Output> {

    @Schema(
        title = "Object key to delete.",
        description = "Full key (path) of the object within the bucket, e.g. `data/2024/report.csv`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> key;

    @Schema(
        title = "Version ID of the object to delete.",
        description = "When set, deletes the specific version. Without this, OBS places a delete marker " +
            "(versioned buckets) or permanently deletes (non-versioned buckets / MinIO)."
    )
    @PluginProperty(group = "advanced")
    private Property<String> versionId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(bucket).as(String.class).orElseThrow();
        var rKey = runContext.render(key).as(String.class).orElseThrow();
        var rVersionId = runContext.render(versionId).as(String.class).orElse(null);

        runContext.logger().debug("Deleting OBS object obs://{}/{}", rBucket, rKey);

        try (var obs = client(runContext)) {
            var req = new DeleteObjectRequest(rBucket, rKey);
            if (rVersionId != null && !rVersionId.isBlank()) {
                req.setVersionId(rVersionId);
            }

            var result = obs.deleteObject(req);

            return Output.builder()
                .deleteMarker(result.isDeleteMarker())
                .versionId(result.getVersionId())
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Whether OBS created a delete marker instead of permanently deleting the object.",
            description = "True only on versioned buckets when `versionId` was not specified. " +
                "On non-versioned buckets and MinIO this is always false."
        )
        private final boolean deleteMarker;

        @Schema(
            title = "Version ID of the delete marker or the deleted version.",
            description = "Non-null only when bucket versioning is enabled."
        )
        private final String versionId;
    }
}
