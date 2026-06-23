package io.kestra.plugin.huawei.iam.tasks;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.ConnectionUtils;
import io.kestra.plugin.huawei.TemporaryCredentialsConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@SuperBuilder
@ToString(exclude = {"token", "password", "domainName"})
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

        **Escape-hatch task:** for zero-wiring workflows, prefer the `temporaryCredentials` block on
        the connection layer (configurable via `pluginDefaults`) so credentials are obtained inline
        without manual output references. Use this task only when you need the raw credential values
        in subsequent steps or external systems.
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
    @PluginProperty(group = "connection", secret = true)
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

    @Schema(
        title = "IAM endpoint domain suffix.",
        description = """
            Domain suffix used to build the IAM endpoint URL.
            Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the European sovereign cloud
            (region `eu-west-101` / EU-Dublin).
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> endpointSuffix = Property.ofValue("myhuaweicloud.com");

    @Override
    public Output run(RunContext runContext) throws Exception {
        return run(runContext, null);
    }

    Output run(RunContext runContext, String endpointOverride) throws Exception {
        var rRegion = runContext.render(region).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "region is required to resolve the IAM endpoint — set the 'region' property (e.g. eu-west-101)"));

        // Map this task's `token` field to `iamToken` in TemporaryCredentialsConfig (different name,
        // same concept — `token` is kept for backward compatibility of this task's public schema).
        var config = TemporaryCredentialsConfig.builder()
            .authMethod(authMethod)
            .iamToken(token)
            .username(username)
            .password(password)
            .domainName(domainName)
            .scope(scope)
            .projectName(projectName)
            .durationSeconds(durationSeconds)
            .endpointSuffix(endpointSuffix)
            .build();

        var temp = ConnectionUtils.exchangeForTemporaryCredentials(runContext, config, rRegion, endpointOverride);

        return Output.builder()
            .accessKeyId(temp.accessKeyId())
            .secretAccessKey(temp.secretAccessKey())
            .securityToken(temp.securityToken())
            .expirationTime(temp.expiresAt())
            .build();
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
