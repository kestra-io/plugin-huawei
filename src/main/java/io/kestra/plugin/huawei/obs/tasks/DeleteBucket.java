package io.kestra.plugin.huawei.obs.tasks;

import com.obs.services.exception.ObsException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.obs.AbstractObs;
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
    title = "Delete a Huawei OBS bucket.",
    description = """
        Deletes an OBS bucket. The bucket must be empty before deletion — OBS does not delete
        non-empty buckets and will return a `BucketNotEmpty` error if objects remain.

        The operation is idempotent by default: if the bucket does not exist, the task succeeds
        silently and reports `deleted: false`. Set `errorOnMissing: true` to fail instead when
        the bucket is absent.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: obs_delete_bucket
                namespace: company.team

                tasks:
                  - id: delete_bucket
                    type: io.kestra.plugin.huawei.obs.tasks.DeleteBucket
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-old-bucket"
                """
        ),
        @Example(
            title = "Delete a bucket and fail if it does not exist",
            full = true,
            code = """
                id: obs_delete_bucket_strict
                namespace: company.team

                tasks:
                  - id: delete_bucket
                    type: io.kestra.plugin.huawei.obs.tasks.DeleteBucket
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-expected-bucket"
                    errorOnMissing: true
                """
        )
    }
)
public class DeleteBucket extends AbstractObs implements RunnableTask<DeleteBucket.Output> {

    @Schema(
        title = "Name of the bucket to delete.",
        description = "The bucket must be empty. OBS will refuse deletion and return `BucketNotEmpty` if any objects remain."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> bucket;

    @Schema(
        title = "Whether to fail when the bucket does not exist.",
        description = """
            When `false` (default), a missing bucket is treated as an idempotent no-op and the task
            succeeds with `deleted: false`. When `true`, the task throws if the bucket is absent
            (detected via a `NoSuchBucket` error code or an HTTP 404 response).
            """
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Property<Boolean> errorOnMissing = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(bucket).as(String.class).orElseThrow();
        var rErrorOnMissing = runContext.render(errorOnMissing).as(Boolean.class).orElse(false);

        runContext.logger().debug("Deleting OBS bucket {}", rBucket);

        try (var obs = client(runContext)) {
            try {
                obs.deleteBucket(rBucket);
                runContext.logger().debug("Bucket {} deleted", rBucket);
                return Output.builder().bucket(rBucket).deleted(true).build();
            } catch (ObsException e) {
                // Real OBS issues a HEAD <bucket>?apiversion region-probe before deleteBucket;
                // for a non-existent bucket that probe returns HTTP 404 with a null/empty error code
                // rather than "NoSuchBucket". MinIO takes the direct deleteBucket path and surfaces
                // "NoSuchBucket" instead. Both signal the same absent-bucket condition.
                var bucketAbsent = "NoSuchBucket".equals(e.getErrorCode()) || e.getResponseCode() == 404;
                if (bucketAbsent) {
                    if (rErrorOnMissing) {
                        throw e;
                    }
                    runContext.logger().debug("Bucket {} did not exist — idempotent success", rBucket);
                    return Output.builder().bucket(rBucket).deleted(false).build();
                }
                throw e;
            }
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Name of the bucket.")
        private final String bucket;

        @Schema(
            title = "Whether the bucket was actually deleted.",
            description = "`true` if OBS deleted the bucket; `false` if it was already absent and `errorOnMissing` is `false`."
        )
        private final boolean deleted;
    }
}
