package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;

public interface AbstractObsInterface {

    @Schema(
        title = "Override for the OBS endpoint URL.",
        description = """
            Full URL of the OBS endpoint to connect to instead of the region-derived default
            (`https://obs.<region>.myhuaweicloud.com`). Required when using S3-compatible endpoints
            such as MinIO (e.g. `http://localhost:9000`). Trailing slashes are stripped automatically.
            """
    )
    @PluginProperty(group = "advanced")
    Property<String> getEndpointOverride();

    @Schema(
        title = "Use path-style access for object keys.",
        description = """
            When `true`, the bucket name is placed in the URL path (`http://host/bucket/key`) instead of
            the virtual-hosted style (`http://bucket.host/key`). Required for MinIO and most
            S3-compatible endpoints. Default is `false` (virtual-hosted style, as used by real OBS).
            """
    )
    @PluginProperty(group = "advanced")
    Property<Boolean> getPathStyleAccess();

    @Schema(
        title = "Request signing algorithm.",
        description = """
            Controls how OBS client signs each request:
            - `OBS` — native Huawei OBS signing (default; use for real OBS endpoints).
            - `V2` — S3 v2 HMAC signing; required for MinIO and other S3-compatible endpoints.
            - `V4` — S3 v4 signing; not compatible with MinIO via the OBS SDK due to a date-format
              mismatch. Do not use `V4` with S3-compatible endpoints.
            """
    )
    @PluginProperty(group = "advanced")
    Property<AuthType> getAuthType();

    @Schema(
        title = "Domain suffix for the region-derived OBS endpoint.",
        description = """
            Suffix appended to build `https://obs.<region>.<endpointSuffix>` when no `endpointOverride`
            is set. Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the European sovereign
            region (e.g. `eu-west-101`). Ignored when `endpointOverride` is set.
            """
    )
    @PluginProperty(group = "advanced")
    Property<String> getEndpointSuffix();
}
