package io.kestra.plugin.huawei.iam.tasks;

import com.huaweicloud.sdk.iam.v3.IamClient;
import com.huaweicloud.sdk.iam.v3.model.KeystoneCreateScopedTokenRequest;
import com.huaweicloud.sdk.iam.v3.model.KeystoneCreateScopedTokenRequestBody;
import com.huaweicloud.sdk.iam.v3.model.ScopeDomainOption;
import com.huaweicloud.sdk.iam.v3.model.ScopeProjectOption;
import com.huaweicloud.sdk.iam.v3.model.ScopedTokenAuth;
import com.huaweicloud.sdk.iam.v3.model.ScopedTokenIdentity;
import com.huaweicloud.sdk.iam.v3.model.TokenSocpeOption;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
import io.kestra.plugin.huawei.ConnectionUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Obtain a short-lived IAM token from Huawei Cloud.",
    description = """
        Exchanges AK/SK credentials for a short-lived IAM token (valid for up to 24 hours by
        default, depending on the account security policy). The token value is returned and can be
        passed as `securityToken` to downstream Huawei Cloud tasks so they authenticate without
        re-supplying the raw AK/SK on every call.

        Use `scope: PROJECT` (the default) when the downstream tasks target a regional service
        (OBS, DMS, ECS, …). `projectId` must be set. Use `scope: DOMAIN` when the downstream
        call requires account-level access (e.g. listing all projects under the domain).
        `domainId` must be set in that case.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Obtain a project-scoped token and use it for an OBS upload.",
            full = true,
            code = """
                id: iam_get_token
                namespace: company.team

                tasks:
                  - id: get_token
                    type: io.kestra.plugin.huawei.iam.tasks.GetToken
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    scope: PROJECT

                  - id: upload
                    type: io.kestra.plugin.huawei.obs.tasks.Upload
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    securityToken: "{{ outputs.get_token.tokenValue }}"
                    region: eu-west-101
                    bucket: my-bucket
                    from: "{{ inputs.file }}"
                    key: uploads/data.csv
                """
        ),
        @Example(
            title = "Obtain a domain-scoped token for account-level IAM operations.",
            full = true,
            code = """
                id: iam_domain_token
                namespace: company.team

                tasks:
                  - id: get_domain_token
                    type: io.kestra.plugin.huawei.iam.tasks.GetToken
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    domainId: "{{ secret('HUAWEI_DOMAIN_ID') }}"
                    scope: DOMAIN
                """
        )
    }
)
public class GetToken extends AbstractConnection implements RunnableTask<GetToken.Output> {

    @Schema(
        title = "Token scope.",
        description = """
            Controls the scope of the issued IAM token:
            - `PROJECT` (default) — scoped to the project identified by `projectId`. Required for
              regional services such as OBS, DMS, and ECS. `projectId` must be set.
            - `DOMAIN` — scoped to the account domain identified by `domainId`. Required for
              account-level operations such as listing all projects. `domainId` must be set.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<TokenScope> scope = Property.ofValue(TokenScope.PROJECT);

    @Override
    public Output run(RunContext runContext) throws Exception {
        return run(runContext, null);
    }

    Output run(RunContext runContext, String endpointOverride) throws Exception {
        var config = huaweiClientConfig(runContext);
        var rScope = runContext.render(scope).as(TokenScope.class)
            .orElseThrow(() -> new IllegalArgumentException("scope is required"));

        validatePreconditions(config, rScope);

        var client = endpointOverride != null
            ? ConnectionUtils.iamClient(config, endpointOverride)
            : ConnectionUtils.iamClient(config);
        var response = client.keystoneCreateScopedToken(buildRequest(config, rScope));

        var tokenValue = response.getXSubjectToken();
        if (tokenValue == null || tokenValue.isBlank()) {
            throw new IllegalStateException(
                "IAM returned a successful response but the X-Subject-Token header is missing — " +
                "check that the IAM endpoint is reachable and that the credentials have the required IAM permissions");
        }

        var expiresAt = parseExpiresAt(response.getToken().getExpiresAt());
        runContext.logger().debug("IAM token obtained, expires at {}", expiresAt);

        return Output.builder()
            .tokenValue(tokenValue)
            .expirationTime(expiresAt)
            .build();
    }

    private static void validatePreconditions(AbstractConnection.HuaweiClientConfig config, TokenScope scope) {
        if (config.accessKeyId() == null || config.secretAccessKey() == null) {
            throw new IllegalArgumentException(
                "accessKeyId and secretAccessKey are required for AK/SK authentication");
        }
        if (scope == TokenScope.PROJECT && (config.projectId() == null || config.projectId().isBlank())) {
            throw new IllegalArgumentException(
                "projectId is required when scope is PROJECT — set the 'projectId' property");
        }
        if (scope == TokenScope.DOMAIN && (config.domainId() == null || config.domainId().isBlank())) {
            throw new IllegalArgumentException(
                "domainId is required when scope is DOMAIN — set the 'domainId' property");
        }
    }

    private static KeystoneCreateScopedTokenRequest buildRequest(
        AbstractConnection.HuaweiClientConfig config,
        TokenScope scope
    ) {
        var scopeOption = new TokenSocpeOption();
        if (scope == TokenScope.PROJECT) {
            scopeOption.withProject(new ScopeProjectOption().withId(config.projectId()));
        } else {
            scopeOption.withDomain(new ScopeDomainOption().withId(config.domainId()));
        }

        // The identity method "hw_sdk_aksk" tells IAM that authentication is provided via
        // AK/SK request signing headers (added automatically by the SDK credential layer).
        var identity = new ScopedTokenIdentity()
            .withMethods(List.of("hw_sdk_aksk"));

        var auth = new ScopedTokenAuth()
            .withIdentity(identity)
            .withScope(scopeOption);

        var body = new KeystoneCreateScopedTokenRequestBody().withAuth(auth);
        return new KeystoneCreateScopedTokenRequest().withBody(body);
    }

    private static Instant parseExpiresAt(String expiresAt) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return Instant.now().plusSeconds(86400);
        }
        try {
            return OffsetDateTime.parse(expiresAt).toInstant();
        } catch (Exception e) {
            // The timestamp format is unexpected; fall back to 24h TTL
            return Instant.now().plusSeconds(86400);
        }
    }

    /** Token scope controlling what resources the issued token may access. */
    public enum TokenScope {
        PROJECT,
        DOMAIN
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "IAM token value.",
            description = """
                Short-lived Huawei Cloud IAM token. Pass this value as `securityToken` on downstream
                Huawei Cloud tasks. **Sensitive — treat as a secret and do not log.**
                """
        )
        @PluginProperty(secret = true)
        private final String tokenValue;

        @Schema(
            title = "Token expiration time.",
            description = "UTC instant at which the IAM token expires. Tokens are typically valid for 24 hours."
        )
        private final Instant expirationTime;
    }
}
