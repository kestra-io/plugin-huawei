package io.kestra.plugin.huawei.obs.tasks;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.obs.AbstractObs;
import io.kestra.plugin.huawei.obs.ListInterface;
import io.kestra.plugin.huawei.obs.ObsService;
import io.kestra.plugin.huawei.obs.models.ObsObject;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
    title = "List objects in a Huawei OBS bucket.",
    description = """
        Lists objects in an OBS bucket, optionally filtered by prefix, delimiter, marker, and a
        client-side regular expression. All pages are iterated automatically; results are returned as a
        single list. Use `maxKeys` to control the page size sent to OBS (not the total result count).
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: obs_list
                namespace: company.team

                tasks:
                  - id: list_objects
                    type: io.kestra.plugin.huawei.obs.tasks.ObsList
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-bucket"
                    prefix: "data/2024/"
                    regexp: ".*\\.csv"
                """
        )
    },
    metrics = {
        @Metric(name = "size", type = Counter.TYPE)
    }
)
public class ObsList extends AbstractObs implements RunnableTask<ObsList.Output>, ListInterface {

    @Schema(title = "OBS bucket name to list objects from.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> bucket;

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
        title = "Maximum total number of objects to return.",
        description = """
            Optional safety cap on the total number of objects this task accumulates in memory. Because the
            output of this task *is* the full object list, a bucket holding millions of matching keys would
            otherwise exhaust the JVM heap. When the match count exceeds this value the task fails fast —
            before the full set is materialised — so the failure is a clear error rather than an
            `OutOfMemoryError`. Leave unset for no cap. Unlike `maxKeys` (which only sizes each OBS page),
            this bounds the total result set.
            """
    )
    @PluginProperty(group = "reliability")
    private Property<Integer> maxResults;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(bucket).as(String.class).orElseThrow();
        var rPrefix = runContext.render(prefix).as(String.class).orElse(null);
        var rDelimiter = runContext.render(delimiter).as(String.class).orElse(null);
        var rMarker = runContext.render(marker).as(String.class).orElse(null);
        var rMaxKeys = runContext.render(maxKeys).as(Integer.class).orElse(1000);
        var rRegexp = runContext.render(regexp).as(String.class).orElse(null);
        var rMaxResults = runContext.render(maxResults).as(Integer.class).orElse(null);

        runContext.logger().debug("Listing OBS bucket {} prefix={} regexp={}", rBucket, rPrefix, rRegexp);

        try (var obs = client(runContext)) {
            // Stream the listing into the output, failing fast if it grows past maxResults so a huge bucket
            // surfaces a clear error instead of an OutOfMemoryError.
            var objects = new ArrayList<ObsObject>();
            ObsService.list(obs, rBucket, rPrefix, rDelimiter, rMarker, rMaxKeys, rRegexp, obj -> {
                if (rMaxResults != null && objects.size() >= rMaxResults) {
                    throw new IllegalStateException(
                        "Listing in obs://" + rBucket + " matched more than maxResults=" + rMaxResults +
                            " objects; narrow the prefix/regexp filter or raise maxResults. Aborted before " +
                            "materialising the full result set to avoid memory exhaustion."
                    );
                }
                objects.add(obj);
            });
            runContext.logger().debug("Listed {} objects", objects.size());
            runContext.metric(Counter.of("size", objects.size()));
            return Output.builder().objects(List.copyOf(objects)).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "List of objects matching the specified filters.")
        private final List<ObsObject> objects;
    }
}
