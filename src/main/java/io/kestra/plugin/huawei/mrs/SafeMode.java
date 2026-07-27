package io.kestra.plugin.huawei.mrs;

/** Cluster authentication mode, mapped to the `safe_mode` field of the `RunJobFlow` request body. */
public enum SafeMode {
    SIMPLE,
    KERBEROS
}
