package io.kestra.plugin.huawei;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.plugin.huawei.iam.GetTemporaryCredentials.AuthMethod;
import io.kestra.plugin.huawei.iam.GetTemporaryCredentials.TokenScope;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Inline IAM credential-exchange configuration, embedded in the connection layer.
 *
 * <p>When set on any OBS or DMS task (directly or via {@code pluginDefaults}), the connection layer
 * will call the Huawei IAM STS API once per task execution and supply the resulting temporary
 * AK/SK + security token to the task, with no manual credential wiring required.
 *
 * <p><strong>Long-running tasks:</strong> the exchange runs once at the start of each execution.
 * For {@code RealtimeTrigger} or long-running {@code Consume} tasks that outlive {@code durationSeconds},
 * the temporary credentials will expire mid-run. Use long-lived AK/SK properties or schedule a
 * refresh externally — inline refresh-on-expiry is not implemented.
 */
@SuperBuilder
@Getter
@EqualsAndHashCode
@NoArgsConstructor
public class TemporaryCredentialsConfig {

    @Schema(
        title = "Authentication method.",
        description = """
            Controls which credentials are used to obtain the session token before exchanging for
            temporary STS credentials.

            - `PASSWORD` (default): provide `username`, `password`, and `domainName`.
            - `TOKEN`: provide an existing `iamToken` (`X-Auth-Token`).
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<AuthMethod> authMethod = Property.ofValue(AuthMethod.PASSWORD);

    @Schema(
        title = "IAM token to exchange (TOKEN method only).",
        description = """
            An existing Huawei Cloud `X-Auth-Token` to exchange for temporary STS credentials.
            Required when `authMethod` is `TOKEN`. **Sensitive — always provide via `{{ secret('NAME') }}`.**
            """
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> iamToken;

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
            Required when `authMethod` is `PASSWORD`. Visible in the Huawei Cloud console under
            **My Credentials → Domain Name**.
            """
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> domainName;

    @Schema(
        title = "Token scope (PASSWORD method only).",
        description = """
            Scope of the session token obtained during password authentication.

            - `PROJECT` (default): token is scoped to the project matching `projectName` (or the
              task's `region` when `projectName` is omitted). Use for most downstream tasks.
            - `DOMAIN`: token is scoped to the domain.
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<TokenScope> scope = Property.ofValue(TokenScope.PROJECT);

    @Schema(
        title = "Project name for project-scoped tokens (PASSWORD method only).",
        description = """
            Overrides the project name used for `scope=PROJECT` token requests.
            Defaults to the task's `region` value when omitted, which is correct for most regions.
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> projectName;

    @Schema(
        title = "Lifetime of the temporary credentials in seconds.",
        description = """
            How long the returned temporary AK/SK/security-token should remain valid.
            Huawei Cloud accepts values between 900 (15 minutes) and 86400 (24 hours).
            Defaults to 900 seconds.
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<Integer> durationSeconds = Property.ofValue(900);

    @Schema(
        title = "Huawei Cloud IAM endpoint suffix.",
        description = """
            Domain suffix used to build the IAM endpoint URL when no explicit endpoint override is set.
            Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the European sovereign cloud
            (region `eu-west-101` / EU-Dublin).
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> endpointSuffix = Property.ofValue("myhuaweicloud.com");

    @Override
    public String toString() {
        return "TemporaryCredentialsConfig[authMethod=" + authMethod +
            ", username=" + username +
            ", password=" + redact(password) +
            ", domainName=" + redact(domainName) +
            ", iamToken=" + redact(iamToken) +
            ", scope=" + scope +
            ", projectName=" + projectName +
            ", durationSeconds=" + durationSeconds +
            ", endpointSuffix=" + endpointSuffix +
            ']';
    }

    private static String redact(Object value) {
        return value == null ? "null" : "****";
    }
}
