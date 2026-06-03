package io.kestra.plugin.huawei.obs;

import com.obs.services.model.AuthTypeEnum;

/**
 * OBS request-signing algorithm variants.
 *
 * <ul>
 *   <li>{@code OBS} — native Huawei OBS signing (default for real OBS endpoints).</li>
 *   <li>{@code V2} — S3 v2 HMAC signing, required for MinIO and other S3-compatible endpoints.</li>
 *   <li>{@code V4} — S3 v4 signing. Not supported by MinIO's OBS SDK compatibility layer due to a date-format
 *       mismatch; use {@code V2} for S3-compatible endpoints.</li>
 * </ul>
 */
public enum AuthType {
    OBS(AuthTypeEnum.OBS),
    V2(AuthTypeEnum.V2),
    V4(AuthTypeEnum.V4);

    private final AuthTypeEnum sdkValue;

    AuthType(AuthTypeEnum sdkValue) {
        this.sdkValue = sdkValue;
    }

    public AuthTypeEnum toSdkEnum() {
        return sdkValue;
    }
}
