package io.kestra.plugin.huawei.obs.tasks;

import com.obs.services.model.DeleteObjectRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.plugin.huawei.obs.AbstractObsTrigger;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow when new objects appear in a Huawei OBS bucket.",
    description = """
        Polls an OBS bucket on a configurable interval and fires an execution when objects matching the
        filter are found. After triggering, `action` controls what happens to the matched objects:
        - `DELETE` — objects are deleted so they are not re-processed on the next poll.
        - `MOVE` — objects are moved to a different prefix/bucket before the next poll.
        - `NONE` — objects are left in place; combine with a narrow `marker` or `regexp` to avoid
          infinite re-triggering.

        The trigger outputs the same structure as the `Downloads` task.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: obs_trigger
                namespace: company.team

                tasks:
                  - id: process
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.objects | length }} new files arrived"

                triggers:
                  - id: watch
                    type: io.kestra.plugin.huawei.obs.tasks.Trigger
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-bucket"
                    prefix: "inbox/"
                    action: DELETE
                    interval: PT60S
                """
        ),
        @Example(
            title = "Trigger and move files to an archive prefix",
            full = true,
            code = """
                id: obs_trigger_move
                namespace: company.team

                tasks:
                  - id: process
                    type: io.kestra.plugin.core.log.Log
                    message: "Processing {{ trigger.objects | length }} files"

                triggers:
                  - id: watch
                    type: io.kestra.plugin.huawei.obs.tasks.Trigger
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-bucket"
                    prefix: "inbox/"
                    action: MOVE
                    moveTo:
                      keyPrefix: "processed/"
                    interval: PT300S
                """
        )
    }
)
public class Trigger extends AbstractObsTrigger
    implements PollingTriggerInterface, TriggerOutput<Downloads.Output>,
    ListInterface, ActionInterface {

    @Schema(title = "OBS bucket to watch.")
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
        title = "Action applied to matched objects after they have been downloaded.",
        description = """
            Use `DELETE` or `MOVE` to prevent the same objects from triggering again on the next poll.
            `NONE` is available but requires the caller to manage re-trigger avoidance (e.g. via a
            narrow regexp or an external marker).
            """
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Action> action = Property.ofValue(Action.DELETE);

    @PluginProperty(group = "processing")
    private MoveTo moveTo;

    @Schema(
        title = "Polling interval.",
        description = "How often the trigger polls the bucket for new objects, as an ISO-8601 duration " +
            "(e.g. `PT60S`, `PT5M`). Defaults to 60 seconds."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Duration interval = Duration.ofSeconds(60);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext triggerContext) throws Exception {
        var runContext = conditionContext.getRunContext();

        var rBucket = runContext.render(bucket).as(String.class).orElseThrow();
        var rPrefix = runContext.render(prefix).as(String.class).orElse(null);
        var rDelimiter = runContext.render(delimiter).as(String.class).orElse(null);
        var rMarker = runContext.render(marker).as(String.class).orElse(null);
        var rMaxKeys = runContext.render(maxKeys).as(Integer.class).orElse(1000);
        var rRegexp = runContext.render(regexp).as(String.class).orElse(null);
        var rAction = runContext.render(action).as(Action.class).orElse(Action.DELETE);

        runContext.logger().debug("Polling OBS bucket obs://{} prefix={}", rBucket, rPrefix);

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
                    runContext.logger().debug("Deleting obs://{}/{}", rBucket, obj.getKey());
                    obs.deleteObject(new DeleteObjectRequest(rBucket, obj.getKey()));
                } else if (rAction == Action.MOVE) {
                    var destKey = rKeyPrefix + obj.getKey();
                    runContext.logger().debug("Moving obs://{}/{} → obs://{}/{}", rBucket, obj.getKey(), rDestBucket, destKey);
                    // Copy is verified before the source is deleted, so a failed copy never loses data.
                    ObsService.move(obs, rBucket, obj.getKey(), rDestBucket, destKey, obj.getEtag(), obj.getSize());
                }
            });

            if (enriched.isEmpty()) {
                return Optional.empty();
            }

            runContext.logger().debug("Found {} new objects in obs://{}", enriched.size(), rBucket);

            var output = Downloads.Output.builder()
                .objects(List.copyOf(enriched))
                .outputFiles(Map.copyOf(outputFiles))
                .build();

            var execution = TriggerService.generateExecution(this, conditionContext, triggerContext, output);
            return Optional.of(execution);
        }
    }
}
