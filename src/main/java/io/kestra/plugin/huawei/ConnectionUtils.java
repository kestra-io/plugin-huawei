package io.kestra.plugin.huawei;

import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.core.auth.GlobalCredentials;
import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.iam.v3.IAMCredentials;
import com.huaweicloud.sdk.iam.v3.IamClient;
import com.huaweicloud.sdk.iam.v3.model.AuthScope;
import com.huaweicloud.sdk.iam.v3.model.AuthScopeDomain;
import com.huaweicloud.sdk.iam.v3.model.AuthScopeProject;
import com.huaweicloud.sdk.iam.v3.model.CreateTemporaryAccessKeyByTokenRequest;
import com.huaweicloud.sdk.iam.v3.model.CreateTemporaryAccessKeyByTokenRequestBody;
import com.huaweicloud.sdk.iam.v3.model.IdentityToken;
import com.huaweicloud.sdk.iam.v3.model.KeystoneCreateUserTokenByPasswordRequest;
import com.huaweicloud.sdk.iam.v3.model.KeystoneCreateUserTokenByPasswordRequestBody;
import com.huaweicloud.sdk.iam.v3.model.PwdAuth;
import com.huaweicloud.sdk.iam.v3.model.PwdIdentity;
import com.huaweicloud.sdk.iam.v3.model.PwdPassword;
import com.huaweicloud.sdk.iam.v3.model.PwdPasswordUser;
import com.huaweicloud.sdk.iam.v3.model.PwdPasswordUserDomain;
import com.huaweicloud.sdk.iam.v3.model.TokenAuth;
import com.huaweicloud.sdk.iam.v3.model.TokenAuthIdentity;
import com.huaweicloud.sdk.iam.v3.region.IamRegion;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.iam.GetTemporaryCredentials.AuthMethod;
import io.kestra.plugin.huawei.iam.GetTemporaryCredentials.TokenScope;
import jakarta.annotation.Nullable;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Static factory for Huawei Cloud SDK credentials and clients.
 *
 * <p>Mirrors the role of {@code io.kestra.plugin.aws.ConnectionUtils}: a single place to translate
 * the plugin's {@link AbstractConnection.HuaweiClientConfig} into typed SDK objects so task
 * implementations stay free of credential-wiring boilerplate.
 *
 * <p>{@link #obtainTokenByPassword} authenticates via the IAM SDK's
 * {@code keystoneCreateUserTokenByPassword} call ({@code POST /v3/auth/tokens}). That endpoint is an
 * unauthenticated Keystone-style login — it ignores the AK/SK signature the SDK client still attaches
 * to every request, so the client is built with a well-formed placeholder credential rather than a
 * real one, which does not exist yet at this bootstrap stage.
 */
public final class ConnectionUtils {

    private ConnectionUtils() {}

    /**
     * Result of an IAM STS credential exchange.
     *
     * <p>Holds temporary AK/SK + security token along with the expiry instant.
     * Its {@link #toString()} redacts secret fields so it can be safely logged.
     */
    public record TemporaryCredentials(
        String accessKeyId,
        String secretAccessKey,
        String securityToken,
        @Nullable Instant expiresAt
    ) {
        @Override
        public String toString() {
            return "TemporaryCredentials[accessKeyId=****, secretAccessKey=****, securityToken=****, expiresAt=" + expiresAt + ']';
        }
    }

    /**
     * Exchanges IAM credentials for short-lived STS temporary credentials.
     *
     * <p>Supports two authentication methods:
     * <ul>
     *   <li>{@link AuthMethod#PASSWORD}: authenticates with IAM username/password to obtain a
     *       session token via {@code POST /v3/auth/tokens}, then exchanges it for STS credentials.</li>
     *   <li>{@link AuthMethod#TOKEN}: exchanges a pre-existing {@code X-Auth-Token} directly.</li>
     * </ul>
     *
     * @param runContext       current run context (logging, rendering)
     * @param config           rendered temporary-credentials configuration
     * @param region           rendered region identifier (e.g. {@code eu-west-101})
     * @param endpointOverride optional IAM endpoint override; {@code null} uses the production endpoint
     *                         derived from {@code region}
     */
    public static TemporaryCredentials exchangeForTemporaryCredentials(
        RunContext runContext,
        TemporaryCredentialsConfig config,
        String region,
        @Nullable String endpointOverride
    ) throws Exception {
        var rAuthMethod = runContext.render(config.getAuthMethod()).as(AuthMethod.class).orElse(AuthMethod.PASSWORD);
        var rDuration = runContext.render(config.getDurationSeconds()).as(Integer.class).orElse(900);
        var rSuffix = runContext.render(config.getEndpointSuffix()).as(String.class).orElse("myhuaweicloud.com");

        // Derive the IAM base URL unless the caller supplied an explicit override (e.g. WireMock in tests).
        if (endpointOverride == null && (region == null || region.isBlank())) {
            throw new IllegalArgumentException(
                "region is required to resolve the IAM endpoint — set the 'region' property (e.g. eu-west-101)");
        }
        var iamBaseUrl = endpointOverride != null
            ? endpointOverride
            : "https://iam." + region + "." + rSuffix;

        var resolvedToken = switch (rAuthMethod) {
            case TOKEN -> resolveIamToken(runContext, config);
            case PASSWORD -> obtainTokenByPassword(runContext, config, region, iamBaseUrl);
        };

        // Both the token call and the STS call must target the same partition.
        var client = IamClient.newBuilder()
            .withCredential(new IAMCredentials().withXAuthToken(resolvedToken))
            .withEndpoint(iamBaseUrl)
            .build();

        var response = client.createTemporaryAccessKeyByToken(buildStsRequest(resolvedToken, rDuration));

        var credential = response.getCredential();
        if (credential == null) {
            throw new IllegalStateException(
                "IAM STS returned a successful response but the credential body is missing — " +
                "check that the token is valid and has not expired");
        }

        var expiresAt = parseExpiresAt(runContext, credential.getExpiresAt());
        runContext.logger().debug("Temporary credentials obtained, expires at {}", expiresAt);

        return new TemporaryCredentials(
            credential.getAccess(),
            credential.getSecret(),
            credential.getSecuritytoken(),
            expiresAt
        );
    }

    private static String resolveIamToken(RunContext runContext, TemporaryCredentialsConfig config) throws Exception {
        return runContext.render(config.getIamToken()).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "iamToken (token) is required when authMethod is TOKEN — provide an existing IAM X-Auth-Token " +
                "or switch to authMethod=PASSWORD to authenticate with username and password"));
    }

    /**
     * A well-formed but meaningless AK/SK pair for the password-bootstrap {@link IamClient}.
     *
     * <p>{@code POST /v3/auth/tokens} is the login call itself — no real credential exists yet — but
     * {@code AKSKSigner} rejects a blank ak/sk client-side before the request is even sent, so a
     * non-blank placeholder is required even though the target endpoint ignores the signature.
     */
    private static GlobalCredentials passwordBootstrapCredentials() {
        return new GlobalCredentials().withAk("iam-password-bootstrap-ak").withSk("iam-password-bootstrap-sk");
    }

    /**
     * Obtains an IAM session token via the IAM SDK's {@code keystoneCreateUserTokenByPassword} call
     * ({@code POST /v3/auth/tokens}).
     */
    private static String obtainTokenByPassword(
        RunContext runContext,
        TemporaryCredentialsConfig config,
        String region,
        String iamBaseUrl
    ) throws Exception {
        var rUsername = runContext.render(config.getUsername()).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "username is required when authMethod is PASSWORD"));
        var rPassword = runContext.render(config.getPassword()).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "password is required when authMethod is PASSWORD"));
        if (rPassword.isBlank()) {
            throw new IllegalArgumentException(
                "password must not be blank — check that the secret resolves to a non-empty value");
        }
        var rDomainName = runContext.render(config.getDomainName()).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "domainName is required when authMethod is PASSWORD — this is the Huawei Cloud " +
                "account name visible under My Credentials → Domain Name in the console"));
        var rScope = runContext.render(config.getScope()).as(TokenScope.class).orElse(TokenScope.PROJECT);
        var rProjectName = runContext.render(config.getProjectName()).as(String.class).orElse(null);

        var request = buildKeystoneRequest(rUsername, rPassword, rDomainName, rScope,
            rProjectName != null ? rProjectName : region);

        try {
            var client = IamClient.newBuilder()
                .withCredential(passwordBootstrapCredentials())
                .withEndpoint(iamBaseUrl)
                .build();

            var response = client.keystoneCreateUserTokenByPassword(request);

            var xSubjectToken = response.getXSubjectToken();
            if (xSubjectToken == null || xSubjectToken.isBlank()) {
                throw new IllegalStateException(
                    "IAM /v3/auth/tokens returned 201 but the X-Subject-Token response header is missing");
            }

            runContext.logger().debug("IAM session token obtained via password authentication");
            return xSubjectToken;
        } catch (ServiceResponseException e) {
            var message = "IAM password authentication failed (HTTP " + e.getHttpStatusCode() + ")" +
                describeIamError(e) +
                " — check that username, password, and domainName are correct and the user is not locked";
            // Only chain the SDK exception as cause when it carries a structured errorCode. When
            // errorCode is null, ServiceResponseException#getMessage() echoes the raw, unparseable
            // response body verbatim — the very content describeIamError deliberately withholds from
            // our message — so re-exposing it through the cause chain would defeat that safeguard.
            throw e.getErrorCode() != null
                ? new IllegalStateException(message, e)
                : new IllegalStateException(message);
        } catch (SdkException e) {
            throw new IllegalStateException("IAM password authentication failed: " + e.getMessage(), e);
        }
    }

    private static KeystoneCreateUserTokenByPasswordRequest buildKeystoneRequest(
        String username, String password, String domainName, TokenScope scope, String resolvedProjectName
    ) {
        var user = new PwdPasswordUser()
            .withName(username)
            .withPassword(password)
            .withDomain(new PwdPasswordUserDomain().withName(domainName));

        var identity = new PwdIdentity()
            .withMethods(List.of(PwdIdentity.MethodsEnum.PASSWORD))
            .withPassword(new PwdPassword().withUser(user));

        var authScope = switch (scope) {
            case PROJECT -> new AuthScope().withProject(new AuthScopeProject().withName(resolvedProjectName));
            case DOMAIN -> new AuthScope().withDomain(new AuthScopeDomain().withName(domainName));
        };

        var auth = new PwdAuth().withIdentity(identity).withScope(authScope);
        var body = new KeystoneCreateUserTokenByPasswordRequestBody().withAuth(auth);
        return new KeystoneCreateUserTokenByPasswordRequest().withBody(body);
    }

    /**
     * Formats the structured {@code code}/{@code message} the SDK extracted from the IAM error body,
     * if any.
     *
     * <p>{@link ServiceResponseException#getErrorCode()} is only populated when the response body was
     * valid JSON matching a recognized error shape (including IAM's Keystone-style nested
     * {@code {"error":{"code":...,"message":...}}}) — never from an unparseable or plain-text body,
     * which the SDK otherwise falls back to echoing verbatim into {@code errorMsg}. Gating on
     * {@code errorCode != null} keeps that raw, potentially sensitive body out of the exception
     * message, matching the previous hand-rolled parser's behavior.
     */
    private static String describeIamError(ServiceResponseException e) {
        if (e.getErrorCode() == null) {
            return "";
        }
        var sb = new StringBuilder(": ");
        if (e.getErrorMsg() != null) {
            sb.append(e.getErrorMsg());
        }
        sb.append(e.getErrorMsg() != null ? " [" : "[").append("code=").append(e.getErrorCode()).append(']');
        return sb.toString();
    }

    private static CreateTemporaryAccessKeyByTokenRequest buildStsRequest(String tokenValue, int duration) {
        var identityToken = new IdentityToken()
            .withId(tokenValue)
            .withDurationSeconds(duration);

        var identity = new TokenAuthIdentity()
            .withMethods(List.of(TokenAuthIdentity.MethodsEnum.TOKEN))
            .withToken(identityToken);

        var auth = new TokenAuth().withIdentity(identity);
        var body = new CreateTemporaryAccessKeyByTokenRequestBody().withAuth(auth);
        return new CreateTemporaryAccessKeyByTokenRequest().withBody(body);
    }

    private static Instant parseExpiresAt(RunContext runContext, String expiresAt) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return Instant.now().plusSeconds(900);
        }
        try {
            return OffsetDateTime.parse(expiresAt).toInstant();
        } catch (Exception e) {
            runContext.logger().warn(
                "Could not parse IAM STS expires_at '{}', defaulting to 900 s — value: {}",
                expiresAt, e.getMessage());
            return Instant.now().plusSeconds(900);
        }
    }

    /**
     * Builds project-scoped (regional) AK/SK credentials.
     *
     * <p>Used for most regional Huawei Cloud services. {@code projectId} scopes the credential to a
     * specific project; when omitted the SDK will attempt to auto-resolve it, which may fail for
     * certain endpoints.
     */
    static BasicCredentials projectCredentials(AbstractConnection.HuaweiClientConfig config) {
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
    static GlobalCredentials globalCredentials(AbstractConnection.HuaweiClientConfig config) {
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
    static IamClient iamClient(AbstractConnection.HuaweiClientConfig config) {
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
    static IamClient iamClient(AbstractConnection.HuaweiClientConfig config, String endpointOverride) {
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
    static IamClient iamClientWithToken(String token, String region) {
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException(
                "region is required to resolve the IAM endpoint — set the 'region' property (e.g. eu-west-101)");
        }
        return IamClient.newBuilder()
            .withCredential(new IAMCredentials().withXAuthToken(token))
            .withRegion(IamRegion.valueOf(region))
            .build();
    }

}
