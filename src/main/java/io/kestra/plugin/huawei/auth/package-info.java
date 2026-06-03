@PluginSubGroup(
    title = "Authentication",
    description = "Tasks that obtain Huawei Cloud IAM authentication material — short-lived tokens that " +
        "downstream Huawei service tasks consume via the `X-Auth-Token` header.",
    categories = { PluginSubGroup.PluginCategory.CLOUD }
)
package io.kestra.plugin.huawei.auth;

import io.kestra.core.models.annotations.PluginSubGroup;
