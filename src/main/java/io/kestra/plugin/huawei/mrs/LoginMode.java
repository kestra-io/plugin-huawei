package io.kestra.plugin.huawei.mrs;

/** Node SSH login method, mapped to the `login_mode` field of the `RunJobFlow` request body. */
public enum LoginMode {
    PASSWORD,
    PUBLICKEY
}
