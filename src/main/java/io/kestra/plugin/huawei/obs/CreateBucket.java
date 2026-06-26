package io.kestra.plugin.huawei.obs;

import com.obs.services.exception.ObsException;
import com.obs.services.model.CreateBucketRequest;
import com.obs.services.model.StorageClassEnum;
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
    title = "Create a Huawei OBS bucket.",
    description = """
        Creates an OBS bucket. The operation is idempotent: if the bucket already exists and is owned by
        the authenticated account, the task succeeds and reports `created: false`. If the bucket exists
        but is owned by a different account, OBS returns a 409 error and the task fails.
        """
)
@Plugin(
    aliases = "io.kestra.plugin.huawei.obs.tasks.CreateBucket",
    examples = {
        @Example(
            full = true,
            code = """
                id: obs_create_bucket
                namespace: company.team

                tasks:
                  - id: create_bucket
                    type: io.kestra.plugin.huawei.obs.CreateBucket
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-new-bucket"
                """
        ),
        @Example(
            title = "Create a bucket with a specific storage class",
            full = true,
            code = """
                id: obs_create_warm_bucket
                namespace: company.team

                tasks:
                  - id: create_warm_bucket
                    type: io.kestra.plugin.huawei.obs.CreateBucket
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-archive-bucket"
                    storageClass: WARM
                """
        )
    }
)
public class CreateBucket extends AbstractObs implements RunnableTask<CreateBucket.Output> {

    @Schema(
        title = "Name of the bucket to create.",
        description = "Bucket names must be globally unique across OBS, follow DNS naming rules (3-63 chars, " +
            "lowercase, numbers, hyphens), and must not start or end with a hyphen."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> bucket;

    @Schema(
        title = "Default storage class for objects in the bucket.",
        description = """
            Controls the default storage tier for new objects:
            - `STANDARD` — frequently accessed data (default).
            - `WARM` — infrequently accessed data; lower cost, retrieval fee applies.
            - `COLD` — archival data; lowest cost, higher retrieval latency.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<StorageClassEnum> storageClass;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(bucket).as(String.class).orElseThrow();
        var rStorageClass = runContext.render(storageClass).as(StorageClassEnum.class).orElse(null);

        runContext.logger().debug("Creating OBS bucket {}", rBucket);

        try (var obs = client(runContext)) {
            var req = new CreateBucketRequest(rBucket);
            if (rStorageClass != null) {
                req.setBucketStorageClass(rStorageClass);
            }

            try {
                obs.createBucket(req);
                runContext.logger().debug("Bucket {} created", rBucket);
                return Output.builder().bucket(rBucket).created(true).build();
            } catch (ObsException e) {
                // 409 BucketAlreadyOwnedByYou or BucketAlreadyExists from the caller's own account — idempotent
                if (isAlreadyOwnedByMe(e)) {
                    runContext.logger().debug("Bucket {} already exists — idempotent success", rBucket);
                    return Output.builder().bucket(rBucket).created(false).build();
                }
                throw e;
            }
        }
    }

    private static boolean isAlreadyOwnedByMe(ObsException e) {
        var code = e.getErrorCode();
        return "BucketAlreadyOwnedByYou".equals(code) || "BucketAlreadyExists".equals(code);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Name of the bucket.")
        private final String bucket;

        @Schema(
            title = "Whether the bucket was newly created.",
            description = "`true` if OBS created the bucket; `false` if it already existed and is owned by this account."
        )
        private final boolean created;
    }
}
