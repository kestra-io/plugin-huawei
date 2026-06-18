package io.kestra.plugin.huawei;

import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.core.auth.GlobalCredentials;
import com.huaweicloud.sdk.iam.v3.IAMCredentials;
import com.huaweicloud.sdk.iam.v3.IamClient;
import com.huaweicloud.sdk.iam.v3.region.IamRegion;

/**
 * Static factory for Huawei Cloud SDK credentials and clients.
 *
 * <p>Mirrors the role of {@code io.kestra.plugin.aws.ConnectionUtils}: a single place to translate
 * the plugin's {@link AbstractConnection.HuaweiClientConfig} into typed SDK objects so task
 * implementations stay free of credential-wiring boilerplate.
 */
public final class ConnectionUtils {

    private ConnectionUtils() {}

    /**
     * Builds project-scoped (regional) AK/SK credentials.
     *
     * <p>Used for most regional Huawei Cloud services. {@code projectId} scopes the credential to a
     * specific project; when omitted the SDK will attempt to auto-resolve it, which may fail for
     * certain endpoints.
     */
    public static BasicCredentials projectCredentials(AbstractConnection.HuaweiClientConfig config) {
        var creds = new BasicCredentials()
            .withAk(config.accessKeyId())
            .withSk(config.secretAccessKey());
        if (config.securityToken() != null) {
            creds.withSecurityToken(config.securityToken());
        }
        if (config.projectId() != null) {
            creds.withProjectId(config.projectId());
        }
        return creds;
    }

    /**
     * Builds domain-scoped (global) AK/SK credentials.
     *
     * <p>Required for global services such as IAM. {@code domainId} identifies the Huawei Cloud
     * account; when omitted the SDK will attempt to auto-resolve it from the AK, which requires
     * an extra network call and may fail in isolated environments.
     */
    public static GlobalCredentials globalCredentials(AbstractConnection.HuaweiClientConfig config) {
        var creds = new GlobalCredentials()
            .withAk(config.accessKeyId())
            .withSk(config.secretAccessKey());
        if (config.securityToken() != null) {
            creds.withSecurityToken(config.securityToken());
        }
        if (config.domainId() != null) {
            creds.withDomainId(config.domainId());
        }
        return creds;
    }

    /**
     * Builds an {@link IamClient} scoped to the region in {@code config}.
     *
     * <p>Uses {@link #globalCredentials(AbstractConnection.HuaweiClientConfig)} because IAM is a
     * global service. The region is still required by the SDK to resolve the IAM endpoint (each
     * region has its own IAM endpoint URL).
     *
     * @throws IllegalArgumentException if {@code config.region()} is null or not a known IAM region
     */
    public static IamClient iamClient(AbstractConnection.HuaweiClientConfig config) {
        if (config.region() == null || config.region().isBlank()) {
            throw new IllegalArgumentException(
                "region is required to resolve the IAM endpoint — set the 'region' property (e.g. eu-west-101)");
        }
        return IamClient.newBuilder()
            .withCredential(globalCredentials(config))
            .withRegion(IamRegion.valueOf(config.region()))
            .build();
    }

    /**
     * Builds an {@link IamClient} that sends all requests to {@code endpointOverride}.
     *
     * <p>Used in tests to point the client at a WireMock server. Not intended for production use.
     */
    public static IamClient iamClient(AbstractConnection.HuaweiClientConfig config, String endpointOverride) {
        return IamClient.newBuilder()
            .withCredential(globalCredentials(config))
            .withEndpoint(endpointOverride)
            .build();
    }

    /**
     * Builds an {@link IamClient} authenticated by an IAM token ({@code X-Auth-Token} header).
     *
     * <p>Used by {@code GetToken} to call the STS API. The STS endpoint authenticates via token,
     * not AK/SK, so {@link IAMCredentials} is used instead of {@link GlobalCredentials}.
     *
     * @throws IllegalArgumentException if {@code region} is null or not a known IAM region
     */
    public static IamClient iamClientWithToken(String token, String region) {
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException(
                "region is required to resolve the IAM endpoint — set the 'region' property (e.g. eu-west-101)");
        }
        return IamClient.newBuilder()
            .withCredential(new IAMCredentials().withXAuthToken(token))
            .withRegion(IamRegion.valueOf(region))
            .build();
    }

    /**
     * Builds a token-authenticated {@link IamClient} that sends all requests to {@code endpointOverride}.
     *
     * <p>Used in tests to point the client at a WireMock server. Not intended for production use.
     */
    public static IamClient iamClientWithToken(String token, String region, String endpointOverride) {
        return IamClient.newBuilder()
            .withCredential(new IAMCredentials().withXAuthToken(token))
            .withEndpoint(endpointOverride)
            .build();
    }
}
