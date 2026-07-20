@PluginSubGroup(
    title = "GeminiDB for NoSQL (DynamoDB-Compatible API)",
    description = "Tasks for Huawei Cloud GeminiDB for NoSQL: put, get, delete, query, and scan items " +
        "against a GeminiDB instance's DynamoDB-Compatible API. Instances are addressed by a " +
        "per-instance `endpoint` connection address, not a region-derived host — `region` is used " +
        "only for SigV4 request signing and has no effect on routing.",
    categories = { PluginSubGroup.PluginCategory.DATA }
)
package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.models.annotations.PluginSubGroup;
