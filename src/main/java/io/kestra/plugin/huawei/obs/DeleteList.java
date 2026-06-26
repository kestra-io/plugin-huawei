package io.kestra.plugin.huawei.obs;

import com.obs.services.model.DeleteObjectsRequest;
import com.obs.services.model.KeyAndVersion;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.obs.AbstractObsObject;
import io.kestra.plugin.huawei.obs.ListInterface;
import io.kestra.plugin.huawei.obs.ObsService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Delete all OBS objects matching a filter.",
    description = """
        Lists objects matching the prefix/regexp filter (reusing the same logic as the `List` task) and
        batch-deletes them in chunks of up to 1000 (the OBS multi-object delete limit). By default the
        task fails if OBS reports any per-object delete error (`errorOnFailure=true`). Set it to `false`
        for best-effort deletion: errors are then logged as warnings and the number of failures is
        surfaced in the `errors` output so the caller can react programmatically. Set `errorOnEmpty` to
        `true` to also fail the task when no objects match the filter.
        """
)
@Plugin(
    aliases = "io.kestra.plugin.huawei.obs.tasks.DeleteList",
    examples = {
        @Example(
            full = true,
            code = """
                id: obs_delete_list
                namespace: company.team

                tasks:
                  - id: delete_prefix
                    type: io.kestra.plugin.huawei.obs.DeleteList
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-bucket"
                    prefix: "tmp/2024/"
                """
        ),
        @Example(
            title = "Delete matching a regexp, fail if no objects found",
            full = true,
            code = """
                id: obs_delete_stale
                namespace: company.team

                tasks:
                  - id: delete_stale
                    type: io.kestra.plugin.huawei.obs.DeleteList
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-bucket"
                    prefix: "reports/"
                    regexp: ".*_stale\\.csv"
                    errorOnEmpty: true
                """
        )
    },
    metrics = {
        @Metric(name = "count", type = Counter.TYPE)
    }
)
public class DeleteList extends AbstractObsObject implements RunnableTask<DeleteList.Output>, ListInterface {

    private static final int BATCH_SIZE = 1000;

    @PluginProperty(group = "processing")
    private Property<String> prefix;

    @PluginProperty(group = "processing")
    private Property<String> delimiter;

    @PluginProperty(group = "processing")
    private Property<String> marker;

    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> maxKeys = Property.ofValue(1000);

    @PluginProperty(group = "processing")
    private Property<String> regexp;

    @Schema(
        title = "Fail the task when no objects match the filter.",
        description = "When `true`, the task throws an exception if the listing returns zero matching objects. " +
            "Useful to catch misconfigured filters in production workflows."
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Boolean> errorOnEmpty = Property.ofValue(false);

    @Schema(
        title = "Fail the task when OBS reports any per-object delete error.",
        description = "When `true` (the default), the task throws after attempting every batch if OBS " +
            "returned one or more error results, so a partial failure cannot be silently swallowed. " +
            "Set to `false` for best-effort deletion: failures are logged as warnings and counted in the " +
            "`errors` output instead of failing the task."
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Boolean> errorOnFailure = Property.ofValue(true);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(bucket).as(String.class).orElseThrow();
        var rPrefix = runContext.render(prefix).as(String.class).orElse(null);
        var rDelimiter = runContext.render(delimiter).as(String.class).orElse(null);
        var rMarker = runContext.render(marker).as(String.class).orElse(null);
        var rMaxKeys = runContext.render(maxKeys).as(Integer.class).orElse(1000);
        var rRegexp = runContext.render(regexp).as(String.class).orElse(null);
        var rErrorOnEmpty = runContext.render(errorOnEmpty).as(Boolean.class).orElse(false);
        var rErrorOnFailure = runContext.render(errorOnFailure).as(Boolean.class).orElse(true);

        runContext.logger().debug("Listing objects to delete in obs://{} prefix={}", rBucket, rPrefix);

        try (var obs = client(runContext)) {
            // Stream the listing and delete page-by-page so we never hold more than BATCH_SIZE keys in
            // memory, regardless of how many objects match the filter.
            var batch = new ArrayList<KeyAndVersion>(BATCH_SIZE);
            // [0] matched, [1] deleted, [2] total size, [3] failed — boxed so the streaming lambda and
            // deleteBatch can mutate them.
            var stats = new long[4];

            ObsService.list(obs, rBucket, rPrefix, rDelimiter, rMarker, rMaxKeys, rRegexp, obj -> {
                stats[0]++;
                stats[2] += obj.getSize() != null ? obj.getSize() : 0L;
                batch.add(new KeyAndVersion(obj.getKey()));
                if (batch.size() >= BATCH_SIZE) {
                    deleteBatch(obs, rBucket, batch, runContext, stats);
                    batch.clear();
                }
            });
            if (!batch.isEmpty()) {
                deleteBatch(obs, rBucket, batch, runContext, stats);
                batch.clear();
            }

            if (stats[0] == 0) {
                if (rErrorOnEmpty) {
                    throw new IllegalStateException(
                        "No objects matched the filter (bucket=" + rBucket + " prefix=" + rPrefix +
                            " regexp=" + rRegexp + ") and errorOnEmpty=true"
                    );
                }
                runContext.logger().debug("No objects matched — nothing to delete");
                return Output.builder().count(0).size(0L).errors(0L).build();
            }

            // Emit the metric before any failure throw so observability captures what was deleted.
            runContext.metric(Counter.of("count", stats[1]));

            if (stats[3] > 0 && rErrorOnFailure) {
                throw new IllegalStateException(
                    "Failed to delete " + stats[3] + " of " + stats[0] + " matched object(s) in obs://" +
                        rBucket + " (deleted=" + stats[1] + "); set errorOnFailure=false to tolerate " +
                        "partial failures and read the `errors` output instead"
                );
            }

            runContext.logger().debug("Deleted {} objects ({} bytes total, {} failed)", stats[1], stats[2], stats[3]);
            return Output.builder().count(stats[1]).size(stats[2]).errors(stats[3]).build();
        }
    }

    /**
     * Deletes one batch of keys (at most {@link #BATCH_SIZE}), accumulating successes into {@code stats[1]}
     * and per-object failures into {@code stats[3]}. Errors are always logged as warnings; whether they
     * fail the task is decided by the caller after all batches complete (see {@code errorOnFailure}).
     */
    private static void deleteBatch(
        com.obs.services.ObsClient obs,
        String bucket,
        List<KeyAndVersion> keys,
        RunContext runContext,
        long[] stats
    ) {
        var req = new DeleteObjectsRequest(bucket, false, keys.toArray(KeyAndVersion[]::new));
        var result = obs.deleteObjects(req);

        stats[1] += result.getDeletedObjectResults().size();

        var errors = result.getErrorResults();
        if (errors != null && !errors.isEmpty()) {
            stats[3] += errors.size();
            errors.forEach(e -> runContext.logger().warn(
                "Failed to delete obs://{}/{}: {} — {}",
                bucket, e.getObjectKey(), e.getErrorCode(), e.getMessage()
            ));
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Number of objects successfully deleted.")
        private final long count;

        @Schema(title = "Total size in bytes of all deleted objects.")
        private final long size;

        @Schema(
            title = "Number of objects OBS reported as failed to delete.",
            description = "Always `0` when `errorOnFailure=true` (the task throws instead). With " +
                "`errorOnFailure=false` this lets the caller detect partial failures programmatically."
        )
        private final long errors;
    }
}
