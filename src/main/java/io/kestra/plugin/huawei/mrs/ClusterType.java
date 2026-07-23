package io.kestra.plugin.huawei.mrs;

/** MRS cluster purpose, mapped to the `cluster_type` field of the `RunJobFlow` request body. */
public enum ClusterType {
    ANALYSIS,
    STREAMING,
    MIXED,
    CUSTOM
}
