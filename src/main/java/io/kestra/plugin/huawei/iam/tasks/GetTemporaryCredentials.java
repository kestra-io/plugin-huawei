package io.kestra.plugin.huawei.iam.tasks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaweicloud.sdk.iam.v3.model.CreateTemporaryAccessKeyByTokenRequest;
import com.huaweicloud.sdk.iam.v3.model.CreateTemporaryAccessKeyByTokenRequestBody;
import com.huaweicloud.sdk.iam.v3.model.IdentityToken;
import com.huaweicloud.sdk.iam.v3.model.TokenAuth;
import com.huaweicloud.sdk.iam.v3.model.TokenAuthIdentity;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.ConnectionUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Obtain short-lived Huawei Cloud credentials (temporary AK/SK + security token).",
    description = """
        Produces temporary credentials valid for up to 24 hours that can be passed directly to
        downstream tasks (OBS, DMS, …) in place of long-lived AK/SK credentials.

        Two authentication methods are supported:

        - **PASSWORD** (default): authenticates with a Huawei Cloud IAM username and password to
          obtain a session token, then exchanges it for temporary STS credentials. No pre-existing
          token is required — this is the recommended entry point for durable-credential workflows.

        - **TOKEN**: exchanges an already-obtained `X-Auth-Token` directly for temporary STS
          credentials. Use this when you manage the IAM token lifecycle externally.

        The returned `accessKeyId`, `secretAccessKey`, and `securityToken` expire at
        `expirationTime`; refresh before that deadline.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Obtain temporary credentials from an IAM username and password, then upload a file to OBS.",
            full = true,
            code = """
                id: iam_password_temp_creds
                namespace: company.team

                tasks:
                  - id: get_temp_creds
                    type: io.kestra.plugin.huawei.iam.tasks.GetTemporaryCredentials
                    region: eu-west-101
                    authMethod: PASSWORD
                    username: my-iam-user
                    password: "{{ secret('HUAWEI_IAM_PASSWORD') }}"
                    domainName: my-account-domain
                    durationSeconds: 3600

                  - id: upload
                    type: io.kestra.plugin.huawei.obs.tasks.Upload
                    accessKeyId: "{{ outputs.get_temp_creds.accessKeyId }}"
                    secretAccessKey: "{{ outputs.get_temp_creds.secretAccessKey }}"
                    securityToken: "{{ outputs.get_temp_creds.securityToken }}"
                    region: eu-west-101
                    bucket: my-bucket
                    from: "{{ inputs.file }}"
                    key: uploads/data.csv
                """
        ),
        @Example(
            title = "Exchange an existing IAM token for temporary credentials.",
            full = true,
            code = """
                id: iam_token_temp_creds
                namespace: company.team

                tasks:
                  - id: get_temp_creds
                    type: io.kestra.plugin.huawei.iam.tasks.GetTemporaryCredentials
                    region: eu-west-101
                    authMethod: TOKEN
                    token: "{{ secret('HUAWEI_IAM_TOKEN') }}"
                    durationSeconds: 3600

                  - id: upload
                    type: io.kestra.plugin.huawei.obs.tasks.Upload
                    accessKeyId: "{{ outputs.get_temp_creds.accessKeyId }}"
                    secretAccessKey: "{{ outputs.get_temp_creds.secretAccessKey }}"
                    securityToken: "{{ outputs.get_temp_creds.securityToken }}"
                    region: eu-west-101
                    bucket: my-bucket
                    from: "{{ inputs.file }}"
                    key: uploads/data.csv
                """
        )
    }
)
public class GetTemporaryCredentials extends Task implements RunnableTask<GetTemporaryCredentials.Output> {

    /**
     * Discriminates which credential set drives the initial authentication step.
     * PASSWORD is the durable-credential entry point; TOKEN is for callers who already hold an IAM token.
     */
    public enum AuthMethod {
        /**
         * Authenticate with IAM username + password. A session token is obtained via
         * {@code POST /v3/auth/tokens} and immediately exchanged for STS credentials.
         */
        PASSWORD,
        /**
         * Use a pre-existing {@code X-Auth-Token} and exchange it directly for STS credentials.
         */
        TOKEN
    }

    /**
     * Determines how the project scope is encoded in the keystone token request.
     */
    public enum TokenScope {
        /**
         * Token is scoped to a project (region). Most downstream services (OBS, DMS) require this.
         */
        PROJECT,
        /**
         * Token is scoped to the domain. Use for domain-wide IAM management operations.
         */
        DOMAIN
    }

    @Schema(
        title = "Authentication method.",
        description = """
            Controls which credentials are used to obtain the session token before exchanging for
            temporary STS credentials.

            - `PASSWORD`: provide `username`, `password`, and `domainName`. Recommended for
              automated workflows — no pre-existing token management needed.
            - `TOKEN`: provide an existing `token` (`X-Auth-Token`). Use when you manage the IAM
              token lifecycle outside this task.
            """
    )
    @NotNull
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<AuthMethod> authMethod = Property.ofValue(AuthMethod.PASSWORD);

    @Schema(
        title = "Huawei Cloud region.",
        description = "Region identifier such as `eu-west-101`, `ap-southeast-1`, or `cn-north-4`. " +
            "Used to resolve the IAM endpoint URL."
    )
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> region;

    @Schema(
        title = "Lifetime of the temporary credentials in seconds.",
        description = """
            How long the returned temporary AK/SK/security-token should remain valid.
            Huawei Cloud accepts values between 900 (15 minutes) and 86400 (24 hours).
            Defaults to 900 seconds if omitted.
            """
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<Integer> durationSeconds = Property.ofValue(900);

    // ── TOKEN path ──────────────────────────────────────────────────────────────

    @Schema(
        title = "IAM token to exchange (TOKEN method only).",
        description = """
            An existing Huawei Cloud `X-Auth-Token` to exchange for temporary STS credentials.
            Required when `authMethod` is `TOKEN`. **Sensitive — always provide via `{{ secret('NAME') }}`.**
            """
    )
    @PluginProperty(group = "main", secret = true)
    private Property<String> token;

    // ── PASSWORD path ────────────────────────────────────────────────────────────

    @Schema(
        title = "IAM username (PASSWORD method only).",
        description = "Huawei Cloud IAM username. Required when `authMethod` is `PASSWORD`."
    )
    @PluginProperty(group = "connection")
    private Property<String> username;

    @Schema(
        title = "IAM password (PASSWORD method only).",
        description = """
            Password for the IAM user identified by `username`.
            Required when `authMethod` is `PASSWORD`.
            **Sensitive — always provide via `{{ secret('NAME') }}`.**
            """
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> password;

    @Schema(
        title = "Account domain name (PASSWORD method only).",
        description = """
            The Huawei Cloud account name (domain name) that owns the IAM user.
            Required when `authMethod` is `PASSWORD`. This is the top-level account identifier,
            not the project or region name — visible in the Huawei Cloud console under
            **My Credentials → Domain Name**.
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> domainName;

    @Schema(
        title = "Token scope (PASSWORD method only).",
        description = """
            Determines the scope of the session token obtained during password authentication.

            - `PROJECT` (default): token is scoped to the project matching `projectName` (or
              `region` when `projectName` is omitted). Use for most downstream tasks (OBS, DMS).
            - `DOMAIN`: token is scoped to the domain. Use for domain-wide IAM management.
            """
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<TokenScope> scope = Property.ofValue(TokenScope.PROJECT);

    @Schema(
        title = "Project name for project-scoped tokens (PASSWORD method only).",
        description = """
            Overrides the project name used for `scope=PROJECT` token requests.
            Defaults to the `region` value when omitted, which is correct for most regions.
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> projectName;

    @Override
    public Output run(RunContext runContext) throws Exception {
        return run(runContext, null);
    }

    Output run(RunContext runContext, String endpointOverride) throws Exception {
        var rRegion = runContext.render(region).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "region is required to resolve the IAM endpoint — set the 'region' property (e.g. eu-west-101)"));
        var rAuthMethod = runContext.render(authMethod).as(AuthMethod.class).orElse(AuthMethod.PASSWORD);
        var rDuration = runContext.render(durationSeconds).as(Integer.class).orElse(900);

        var resolvedToken = switch (rAuthMethod) {
            case TOKEN -> resolveTokenFromProperty(runContext);
            case PASSWORD -> obtainTokenByPassword(runContext, rRegion, endpointOverride);
        };

        var client = endpointOverride != null
            ? ConnectionUtils.iamClientWithToken(resolvedToken, rRegion, endpointOverride)
            : ConnectionUtils.iamClientWithToken(resolvedToken, rRegion);

        var response = client.createTemporaryAccessKeyByToken(buildStsRequest(resolvedToken, rDuration));

        var credential = response.getCredential();
        if (credential == null) {
            throw new IllegalStateException(
                "IAM STS returned a successful response but the credential body is missing — " +
                "check that the token is valid and has not expired");
        }

        var expirationTime = parseExpiresAt(runContext, credential.getExpiresAt());
        runContext.logger().debug("Temporary credentials obtained, expires at {}", expirationTime);

        return Output.builder()
            .accessKeyId(credential.getAccess())
            .secretAccessKey(credential.getSecret())
            .securityToken(credential.getSecuritytoken())
            .expirationTime(expirationTime)
            .build();
    }

    private String resolveTokenFromProperty(RunContext runContext) throws Exception {
        return runContext.render(token).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "token is required when authMethod is TOKEN — provide an existing IAM X-Auth-Token " +
                "or switch to authMethod=PASSWORD to authenticate with username and password"));
    }

    /**
     * Obtains an IAM session token via {@code POST /v3/auth/tokens}.
     *
     * <p>This is an unauthenticated endpoint (it IS the login), so the Huawei SDK cannot be used
     * here — there is no valid credential to supply to the client builder before the call succeeds.
     * We use {@code java.net.http.HttpClient} directly instead, which keeps the credential-less
     * nature explicit and avoids surprising NPE behaviour in SDK internals.
     */
    private String obtainTokenByPassword(RunContext runContext, String rRegion, String endpointOverride) throws Exception {
        var rUsername = runContext.render(username).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "username is required when authMethod is PASSWORD"));
        var rPassword = runContext.render(password).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "password is required when authMethod is PASSWORD"));
        var rDomainName = runContext.render(domainName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "domainName is required when authMethod is PASSWORD — this is the Huawei Cloud " +
                "account name visible under My Credentials → Domain Name in the console"));
        var rScope = runContext.render(scope).as(TokenScope.class).orElse(TokenScope.PROJECT);
        var rProjectName = runContext.render(projectName).as(String.class).orElse(null);

        var iamEndpoint = endpointOverride != null
            ? endpointOverride
            : "https://iam." + rRegion + ".myhuaweicloud.com";

        var scopeJson = buildScopeJson(rScope, rDomainName, rProjectName != null ? rProjectName : rRegion);
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
            .uri(URI.create(iamEndpoint + "/v3/auth/tokens"))
            .header("Content-Type", "application/json;charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        var httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (httpResponse.statusCode() != 201) {
            throw new IllegalStateException(
                "IAM password authentication failed (HTTP " + httpResponse.statusCode() + ") — " +
                "check that username, password, and domainName are correct and the user is not locked");
        }

        var xSubjectToken = httpResponse.headers().firstValue("X-Subject-Token").orElse(null);
        if (xSubjectToken == null || xSubjectToken.isBlank()) {
            throw new IllegalStateException(
                "IAM /v3/auth/tokens returned 201 but the X-Subject-Token response header is missing");
        }

        runContext.logger().debug("IAM session token obtained via password authentication");
        return xSubjectToken;
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

    private Instant parseExpiresAt(RunContext runContext, String expiresAt) {
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

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Temporary Access Key ID.",
            description = "Short-lived Huawei Cloud access key. Pass as `accessKeyId` to downstream tasks."
        )
        @PluginProperty(group = "connection", secret = true)
        private final String accessKeyId;

        @Schema(
            title = "Temporary Secret Access Key.",
            description = """
                Short-lived Huawei Cloud secret key paired with `accessKeyId`.
                **Sensitive — treat as a secret and do not log.**
                """
        )
        @PluginProperty(group = "connection", secret = true)
        private final String secretAccessKey;

        @Schema(
            title = "Security Token (session token).",
            description = """
                Short-lived security token required alongside the temporary AK/SK.
                Pass as `securityToken` to downstream tasks.
                **Sensitive — treat as a secret and do not log.**
                """
        )
        @PluginProperty(group = "connection", secret = true)
        private final String securityToken;

        @Schema(
            title = "Credential expiration time.",
            description = "UTC instant at which the temporary credentials expire."
        )
        @PluginProperty(group = "connection")
        private final Instant expirationTime;
    }
}
