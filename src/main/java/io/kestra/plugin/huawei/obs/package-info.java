@PluginSubGroup(
    title = "Object Storage Service (OBS)",
    description = "Tasks for interacting with Huawei Cloud OBS — upload, download, and list objects in OBS buckets. " +
        "Compatible with S3-compatible endpoints such as MinIO when `pathStyleAccess` and `authType` are configured.",
    categories = { PluginSubGroup.PluginCategory.STORAGE }
)
package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.annotations.PluginSubGroup;
