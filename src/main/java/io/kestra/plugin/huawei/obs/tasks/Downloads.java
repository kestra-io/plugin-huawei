package io.kestra.plugin.huawei.obs.tasks;

import com.obs.services.model.DeleteObjectRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.obs.AbstractObs;
import io.kestra.plugin.huawei.obs.ActionInterface;
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

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Download all OBS objects matching a filter.",
    description = """
        Lists objects matching the prefix/regexp filter, downloads each one into Kestra internal storage,
        and optionally applies a post-download action (`NONE`, `DELETE`, or `MOVE`). The task outputs both
        the enriched object list and a map of object key to internal storage URI.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: obs_downloads
                namespace: company.team

                tasks:
                  - id: downloads
                    type: io.kestra.plugin.huawei.obs.tasks.Downloads
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-bucket"
                    prefix: "reports/2024/"
                    action: DELETE
                """
        ),
        @Example(
            title = "Download and move to an archive prefix",
            full = true,
            code = """
                id: obs_downloads_move
                namespace: company.team

                tasks:
                  - id: downloads
                    type: io.kestra.plugin.huawei.obs.tasks.Downloads
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-bucket"
                    prefix: "inbox/"
                    action: MOVE
                    moveTo:
                      keyPrefix: "processed/"
                """
        )
    },
    metrics = {
        @Metric(name = "size", type = Counter.TYPE)
    }
)
public class Downloads extends AbstractObs implements RunnableTask<Downloads.Output>, ListInterface, ActionInterface {

    @Schema(title = "OBS bucket to list and download from.")
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

    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Action> action = Property.ofValue(Action.NONE);

    @PluginProperty(group = "processing")
    private MoveTo moveTo;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(bucket).as(String.class).orElseThrow();
        var rPrefix = runContext.render(prefix).as(String.class).orElse(null);
        var rDelimiter = runContext.render(delimiter).as(String.class).orElse(null);
        var rMarker = runContext.render(marker).as(String.class).orElse(null);
        var rMaxKeys = runContext.render(maxKeys).as(Integer.class).orElse(1000);
        var rRegexp = runContext.render(regexp).as(String.class).orElse(null);
        var rAction = runContext.render(action).as(Action.class).orElse(Action.NONE);

        runContext.logger().debug("Listing and downloading objects from s3://{} prefix={}", rBucket, rPrefix);

        // Resolve MOVE destination up front so we validate config before streaming and keep the lambda lean.
        final String rDestBucket;
        final String rKeyPrefix;
        if (rAction == Action.MOVE) {
            if (moveTo == null) {
                throw new IllegalArgumentException("action=MOVE requires moveTo to be configured");
            }
            rDestBucket = runContext.render(moveTo.getBucket()).as(String.class).orElse(rBucket);
            rKeyPrefix = runContext.render(moveTo.getKeyPrefix()).as(String.class).orElse("");
        } else {
            rDestBucket = null;
            rKeyPrefix = null;
        }

        try (var obs = client(runContext)) {
            var enriched = new ArrayList<ObsObject>();
            var outputFiles = new HashMap<String, URI>();

            // Stream the listing: download and apply the action per object in a single pass, so we never
            // hold the full listing in addition to the output.
            ObsService.list(obs, rBucket, rPrefix, rDelimiter, rMarker, rMaxKeys, rRegexp, obj -> {
                var result = ObsService.download(obs, runContext, rBucket, obj.getKey(), null);
                enriched.add(ObsObject.builder()
                    .key(obj.getKey())
                    .etag(obj.getEtag())
                    .size(obj.getSize())
                    .lastModified(obj.getLastModified())
                    .owner(obj.getOwner())
                    .uri(result.uri())
                    .build());
                outputFiles.put(obj.getKey(), result.uri());

                if (rAction == Action.DELETE) {
                    runContext.logger().debug("Deleting s3://{}/{}", rBucket, obj.getKey());
                    obs.deleteObject(new DeleteObjectRequest(rBucket, obj.getKey()));
                } else if (rAction == Action.MOVE) {
                    var destKey = rKeyPrefix + obj.getKey();
                    runContext.logger().debug("Moving s3://{}/{} → s3://{}/{}", rBucket, obj.getKey(), rDestBucket, destKey);
                    // Copy is verified before the source is deleted, so a failed copy never loses data.
                    ObsService.move(obs, rBucket, obj.getKey(), rDestBucket, destKey, obj.getEtag(), obj.getSize());
                }
            });

            runContext.metric(Counter.of("size", enriched.size()));
            return Output.builder()
                .objects(List.copyOf(enriched))
                .outputFiles(Map.copyOf(outputFiles))
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "List of downloaded objects with internal storage URIs.",
            description = "Each entry includes the original OBS metadata plus the `uri` field pointing to " +
                "the downloaded file in Kestra internal storage."
        )
        private final List<ObsObject> objects;

        @Schema(
            title = "Map of object key to Kestra internal storage URI.",
            description = "Convenient for downstream tasks that need to look up a file by its original OBS key."
        )
        private final Map<String, URI> outputFiles;
    }
}
