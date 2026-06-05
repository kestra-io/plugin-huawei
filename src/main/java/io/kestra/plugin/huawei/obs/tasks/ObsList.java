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

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(bucket).as(String.class).orElseThrow();
        var rPrefix = runContext.render(prefix).as(String.class).orElse(null);
        var rDelimiter = runContext.render(delimiter).as(String.class).orElse(null);
        var rMarker = runContext.render(marker).as(String.class).orElse(null);
        var rMaxKeys = runContext.render(maxKeys).as(Integer.class).orElse(1000);
        var rRegexp = runContext.render(regexp).as(String.class).orElse(null);

        runContext.logger().debug("Listing OBS bucket {} prefix={} regexp={}", rBucket, rPrefix, rRegexp);

        try (var obs = client(runContext)) {
            var objects = ObsService.list(obs, rBucket, rPrefix, rDelimiter, rMarker, rMaxKeys, rRegexp);
            runContext.logger().debug("Listed {} objects", objects.size());
            runContext.metric(Counter.of("size", objects.size()));
            return Output.builder().objects(objects).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "List of objects matching the specified filters.")
        private final List<ObsObject> objects;
    }
}
