package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Shared post-download action schema for {@link io.kestra.plugin.huawei.obs.tasks.Downloads} and
 * {@link io.kestra.plugin.huawei.obs.tasks.Trigger}.
 *
 * <p>After objects are downloaded, this interface controls whether the source objects are left in place
 * ({@code NONE}), deleted ({@code DELETE}), or moved to another location ({@code MOVE}).
 */
public interface ActionInterface {

    /**
     * Post-download action applied to each matched source object.
     *
     * <p>Possible values:
     * <ul>
     *   <li>{@code NONE} — source objects are not modified (default for manual tasks).</li>
     *   <li>{@code DELETE} — source objects are deleted after successful download.</li>
     *   <li>{@code MOVE} — source objects are copied to the destination defined by {@link #getMoveTo()},
     *       then deleted from the source. Requires {@code moveTo} to be configured.</li>
     * </ul>
     */
    @Schema(title = "Action to apply to each matched source object after it has been downloaded.")
    @PluginProperty(group = "processing")
    Property<Action> getAction();

    @Schema(title = "Destination bucket and key-prefix when action is MOVE.")
    @PluginProperty(group = "processing")
    MoveTo getMoveTo();

    enum Action {
        NONE,
        DELETE,
        MOVE
    }

    @lombok.Value
    @lombok.Builder
    @lombok.extern.jackson.Jacksonized
    class MoveTo {

        @Schema(title = "Destination bucket. Defaults to the source bucket when not set.")
        @PluginProperty(group = "destination")
        Property<String> bucket;

        @Schema(
            title = "Key prefix prepended to the original object key in the destination.",
            description = """
                The final destination key is built as `<keyPrefix><originalKey>`. For example, with
                `keyPrefix: processed/` and a source key `data/file.csv`, the destination key becomes
                `processed/data/file.csv`. Leave blank to preserve the original key structure.
                """
        )
        @PluginProperty(group = "destination")
        Property<String> keyPrefix;
    }
}
