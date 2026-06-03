package io.kestra.plugin.huawei.obs.tasks;

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
        batch-deletes them in chunks of up to 1000 (the OBS multi-object delete limit). Error results
        from OBS are logged as warnings; the task does not fail on partial errors — check the `count`
        output to detect discrepancies. Set `errorOnEmpty` to `true` to fail the task when no objects
        match the filter.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: obs_delete_list
                namespace: company.team

                tasks:
                  - id: delete_prefix
                    type: io.kestra.plugin.huawei.obs.tasks.DeleteList
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
                    type: io.kestra.plugin.huawei.obs.tasks.DeleteList
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

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(bucket).as(String.class).orElseThrow();
        var rPrefix = runContext.render(prefix).as(String.class).orElse(null);
        var rDelimiter = runContext.render(delimiter).as(String.class).orElse(null);
        var rMarker = runContext.render(marker).as(String.class).orElse(null);
        var rMaxKeys = runContext.render(maxKeys).as(Integer.class).orElse(1000);
        var rRegexp = runContext.render(regexp).as(String.class).orElse(null);
        var rErrorOnEmpty = runContext.render(errorOnEmpty).as(Boolean.class).orElse(false);

        runContext.logger().debug("Listing objects to delete in s3://{} prefix={}", rBucket, rPrefix);

        try (var obs = client(runContext)) {
            var objects = ObsService.list(obs, rBucket, rPrefix, rDelimiter, rMarker, rMaxKeys, rRegexp);

            if (objects.isEmpty()) {
                if (rErrorOnEmpty) {
                    throw new IllegalStateException(
                        "No objects matched the filter (bucket=" + rBucket + " prefix=" + rPrefix +
                            " regexp=" + rRegexp + ") and errorOnEmpty=true"
                    );
                }
                runContext.logger().debug("No objects matched — nothing to delete");
                return Output.builder().count(0).size(0L).build();
            }

            long totalSize = objects.stream().mapToLong(o -> o.getSize() != null ? o.getSize() : 0L).sum();
            runContext.logger().debug("Deleting {} objects ({} bytes total)", objects.size(), totalSize);

            int deleted = 0;
            for (int i = 0; i < objects.size(); i += BATCH_SIZE) {
                var chunk = objects.subList(i, Math.min(i + BATCH_SIZE, objects.size()));
                var kvs = chunk.stream()
                    .map(o -> new KeyAndVersion(o.getKey()))
                    .toList();

                var req = new DeleteObjectsRequest(rBucket, false, kvs.toArray(KeyAndVersion[]::new));
                var result = obs.deleteObjects(req);

                deleted += result.getDeletedObjectResults().size();

                var errors = result.getErrorResults();
                if (errors != null && !errors.isEmpty()) {
                    errors.forEach(e -> runContext.logger().warn(
                        "Failed to delete s3://{}/{}: {} — {}",
                        rBucket, e.getObjectKey(), e.getErrorCode(), e.getMessage()
                    ));
                }
            }

            runContext.metric(Counter.of("count", deleted));
            return Output.builder().count(deleted).size(totalSize).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Number of objects successfully deleted.")
        private final long count;

        @Schema(title = "Total size in bytes of all deleted objects.")
        private final long size;
    }
}
