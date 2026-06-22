package io.kestra.plugin.huawei;

import com.fasterxml.jackson.databind.JsonNode;
import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.core.auth.GlobalCredentials;
import com.huaweicloud.sdk.iam.v3.IAMCredentials;
import com.huaweicloud.sdk.iam.v3.IamClient;
import com.huaweicloud.sdk.iam.v3.model.CreateTemporaryAccessKeyByTokenRequest;
import com.huaweicloud.sdk.iam.v3.model.CreateTemporaryAccessKeyByTokenRequestBody;
import com.huaweicloud.sdk.iam.v3.model.IdentityToken;
import com.huaweicloud.sdk.iam.v3.model.TokenAuth;
import com.huaweicloud.sdk.iam.v3.model.TokenAuthIdentity;
import com.huaweicloud.sdk.iam.v3.region.IamRegion;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.huawei.iam.tasks.GetTemporaryCredentials.AuthMethod;
import io.kestra.plugin.huawei.iam.tasks.GetTemporaryCredentials.TokenScope;
import jakarta.annotation.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

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
        var iamBaseUrl = endpointOverride != null
            ? endpointOverride
            : "https://iam." + region + "." + rSuffix;

        var resolvedToken = switch (rAuthMethod) {
            case TOKEN -> resolveIamToken(runContext, config);
            case PASSWORD -> obtainTokenByPassword(runContext, config, region, iamBaseUrl);
        };

        // Both the token call and the STS call must target the same partition.
        var client = iamClientWithToken(resolvedToken, region, iamBaseUrl);

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
     * Obtains an IAM session token via {@code POST /v3/auth/tokens}.
     *
     * <p>This is an unauthenticated endpoint (it IS the login), so the Huawei SDK cannot be used
     * here — there is no valid credential to supply to the client builder before the call succeeds.
     * We use {@code java.net.http.HttpClient} directly, which keeps the credential-less nature explicit.
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
        var rDomainName = runContext.render(config.getDomainName()).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "domainName is required when authMethod is PASSWORD — this is the Huawei Cloud " +
                "account name visible under My Credentials → Domain Name in the console"));
        var rScope = runContext.render(config.getScope()).as(TokenScope.class).orElse(TokenScope.PROJECT);
        var rProjectName = runContext.render(config.getProjectName()).as(String.class).orElse(null);

        var scopeJson = buildScopeJson(rScope, rDomainName, rProjectName != null ? rProjectName : region);
        var requestBody = """
            {
              "auth": {
                "identity": {
                  "methods": ["password"],
                  "password": {
                    "user": {
                      "name": %s,
                      "password": %s,
                      "domain": { "name": %s }
                    }
                  }
                },
                "scope": %s
              }
            }
            """.formatted(
            jsonString(rUsername),
            jsonString(rPassword),
            jsonString(rDomainName),
            scopeJson
        );

        var httpClient = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
            .uri(URI.create(iamBaseUrl + "/v3/auth/tokens"))
            .header("Content-Type", "application/json;charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        var httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (httpResponse.statusCode() != 201) {
            throw new IllegalStateException(
                "IAM password authentication failed (HTTP " + httpResponse.statusCode() + ")" +
                parseIamError(httpResponse.body()) +
                " — check that username, password, and domainName are correct and the user is not locked");
        }

        var xSubjectToken = httpResponse.headers().firstValue("X-Subject-Token").orElse(null);
        if (xSubjectToken == null || xSubjectToken.isBlank()) {
            throw new IllegalStateException(
                "IAM /v3/auth/tokens returned 201 but the X-Subject-Token response header is missing");
        }

        runContext.logger().debug("IAM session token obtained via password authentication");
        return xSubjectToken;
    }

    /**
     * Parses Huawei IAM error JSON ({@code {"error":{"code":...,"message":...,"title":...}}}) and
     * returns a formatted detail string to append to exception messages.
     *
     * <p>Only the three safe fields from the error object are included — the response body for
     * this endpoint never echoes back submitted credentials.  Falls back to a truncated raw body
     * when the JSON shape is absent or unparseable.
     */
    private static String parseIamError(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            var root = JacksonMapper.ofJson().readTree(body);
            var error = root.path("error");
            if (!error.isMissingNode()) {
                var sb = new StringBuilder(": ");
                var message = textOrNull(error.path("message"));
                var code = textOrNull(error.path("code"));
                var title = textOrNull(error.path("title"));
                if (message != null) {
                    sb.append(message);
                }
                if (code != null) {
                    sb.append(message != null ? " [" : "[").append("code=").append(code);
                    if (title != null) {
                        sb.append(", title=").append(title);
                    }
                    sb.append(']');
                } else if (title != null) {
                    sb.append(message != null ? " [" : "[").append(title).append(']');
                }
                return sb.length() > 2 ? sb.toString() : "";
            }
        } catch (Exception ignored) {
            // JSON parse failure — fall back to safe raw body excerpt
        }
        // Truncate to avoid oversized messages; 200 chars is enough to show the error reason
        var excerpt = body.length() > 200 ? body.substring(0, 200) + "…" : body;
        return ": " + excerpt;
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static String buildScopeJson(TokenScope scope, String domainName, String projectName) {
        return switch (scope) {
            case PROJECT -> """
                { "project": { "name": %s } }
                """.formatted(jsonString(projectName)).strip();
            case DOMAIN -> """
                { "domain": { "name": %s } }
                """.formatted(jsonString(domainName)).strip();
        };
    }

    /** Produces a JSON-safe quoted string, escaping backslash and double-quote characters. */
    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
