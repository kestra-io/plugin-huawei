package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Shared listing configuration for OBS list operations.
 *
 * <p>Implemented by every OBS listing task (List, DeleteList, Downloads, Trigger) so they share a single
 * property schema and reuse {@link ObsService#list} as-is.
 */
public interface ListInterface {

    @Schema(
        title = "Limits the response to keys that begin with the specified prefix"
    )
    @PluginProperty(group = "processing")
    Property<String> getPrefix();

    @Schema(
        title = "A delimiter that groups keys",
        description = """
            Keys containing the delimiter after the prefix are grouped under a common prefix in the
            response. Useful for emulating a folder hierarchy (e.g. `delimiter: /`).
            """
    )
    @PluginProperty(group = "processing")
    Property<String> getDelimiter();

    @Schema(
        title = "Marker to start listing from (exclusive)",
        description = "Returned objects will appear after this key in lexicographic order. " +
            "Use the `nextMarker` from a previous response to paginate."
    )
    @PluginProperty(group = "processing")
    Property<String> getMarker();

    @Schema(
        title = "Maximum number of keys to return per page (default 1000)",
        description = """
            The SDK may return fewer keys than requested if the page boundary falls mid-prefix.
            `ObsService.list` iterates all pages automatically so the total result set is not bounded
            by this value — it only affects the page size sent to OBS.
            """
    )
    @PluginProperty(group = "processing")
    Property<Integer> getMaxKeys();

    @Schema(
        title = "Client-side regular expression applied to the full object key after server-side listing",
        description = """
            Only objects whose key matches this pattern (evaluated with `String.matches`) are included
            in the output. Applied after prefix/delimiter filtering done by OBS, so the regexp sees the
            complete key including any prefix.
            """
    )
    @PluginProperty(group = "processing")
    Property<String> getRegexp();
}
