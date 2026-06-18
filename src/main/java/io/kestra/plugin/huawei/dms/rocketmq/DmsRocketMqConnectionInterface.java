package io.kestra.plugin.huawei.dms.rocketmq;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Shared property contract for all DMS for RocketMQ tasks and triggers.
 */
public interface DmsRocketMqConnectionInterface {

    @Schema(
        title = "Name server address.",
        description = "Address of the RocketMQ name server, e.g. `dms-host:8100`. For DMS for RocketMQ, " +
            "copy the name server address from the instance detail page in the Huawei Cloud console."
    )
    @NotNull
    @PluginProperty(group = "connection")
    Property<String> getNameServerAddr();

    @Schema(
        title = "DMS instance ID.",
        description = "Huawei Cloud DMS for RocketMQ instance ID. Required when the instance uses instance isolation. " +
            "Leave empty for shared DMS instances."
    )
    @PluginProperty(group = "connection")
    Property<String> getInstanceId();

    @Schema(title = "Topic to publish to or consume from.")
    @NotNull
    @PluginProperty(group = "main")
    Property<String> getTopic();

    @Schema(
        title = "Consumer or producer group ID.",
        description = "Consumer group name for Consume/Trigger tasks; producer group name for Publish tasks."
    )
    @PluginProperty(group = "main")
    Property<String> getGroupId();

    @Schema(
        title = "Tag filter expression.",
        description = "Server-side filter applied by the broker. Use `*` (default) to receive all tags, " +
            "or a specific tag to filter messages."
    )
    @PluginProperty(group = "processing")
    Property<String> getTags();

    @Schema(
        title = "Message body serializer/deserializer.",
        description = "`STRING` (default) or `JSON`."
    )
    @PluginProperty(group = "processing")
    Property<RocketMqSerdeType> getSerdeType();
}
