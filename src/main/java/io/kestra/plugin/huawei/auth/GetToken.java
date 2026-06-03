package io.kestra.plugin.huawei.auth;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.EncryptedString;
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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Obtain a short-lived Huawei Cloud IAM token.",
    description = "Calls the Keystone v3 `/v3/auth/tokens` endpoint with password-based identity and returns " +
        "the resulting token (24-hour default lifetime). The token is read from the `X-Subject-Token` response " +
        "header and surfaced as an `EncryptedString` in the task output. Use the output token in downstream " +
        "Huawei tasks by passing it as `securityToken`."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: huawei_get_token
                namespace: company.team

                tasks:
                  - id: get_token
                    type: io.kestra.plugin.huawei.auth.GetToken
                    region: "eu-west-101"
                    username: "{{ secret('HUAWEI_USERNAME') }}"
                    password: "{{ secret('HUAWEI_PASSWORD') }}"
                    userDomain: "{{ secret('HUAWEI_DOMAIN_NAME') }}"
                    projectName: "eu-west-101"
                """
        )
    }
)
public class GetToken extends AbstractConnection implements RunnableTask<GetToken.Output> {

    @Schema(
        title = "IAM user name to authenticate as."
    )
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> username;

    @Schema(
        title = "IAM user password.",
        description = "**Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    private Property<String> password;

    @Schema(
        title = "Huawei Cloud account (domain) name the user belongs to.",
        description = "Distinct from the per-task `domainId`/`domainName` scope settings — this is the domain " +
            "the IAM user is registered under, typically the main Huawei Cloud account name."
    )
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> userDomain;

    @Schema(
        title = "Project name to scope the issued token to.",
        description = "Project-scoped tokens are required for regional services. Mutually exclusive with " +
            "`domainName`."
    )
    @PluginProperty(group = "main")
    private Property<String> projectName;

    @Schema(
        title = "Domain name to scope the issued token to.",
        description = "Domain-scoped tokens are required for global services such as IAM administration. " +
            "Mutually exclusive with `projectName`."
    )
    @PluginProperty(group = "main")
    private Property<String> domainName;

    @Schema(
        title = "HTTP request timeout for the IAM call (default 30s)."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> requestTimeout = Property.ofValue(Duration.ofSeconds(30));

    @Override
    public Output run(RunContext runContext) throws Exception {
        final AbstractConnection.HuaweiClientConfig config = this.huaweiClientConfig(runContext);

        final String rUsername = runContext.render(this.username).as(String.class).orElseThrow();
        final String rPassword = runContext.render(this.password).as(String.class).orElseThrow();
        final String rUserDomain = runContext.render(this.userDomain).as(String.class).orElseThrow();
        final String rProjectName = runContext.render(this.projectName).as(String.class).orElse(null);
        final String rDomainName = runContext.render(this.domainName).as(String.class).orElse(null);
        final Duration rTimeout = runContext.render(this.requestTimeout).as(Duration.class).orElse(Duration.ofSeconds(30));

        final String tokenUrl = ConnectionUtils.iamTokenUrl(config);
        final String body = ConnectionUtils.passwordTokenRequestBody(
            rUsername, rPassword, rUserDomain, rProjectName, rDomainName
        );

        runContext.logger().debug("Requesting Huawei IAM token at {}", tokenUrl);

        var httpConfig = HttpConfiguration.builder()
            .connectTimeout(rTimeout)
            .readTimeout(rTimeout)
            .allowFailed(Property.ofValue(true))
            .build();

        try (var http = new HttpClient(runContext, httpConfig)) {
            var request = HttpRequest.builder()
                .uri(URI.create(tokenUrl))
                .method("POST")
                .addHeader("Content-Type", "application/json;charset=utf-8")
                .body(HttpRequest.StringRequestBody.builder()
                    .content(body)
                    .contentType("application/json")
                    .charset(StandardCharsets.UTF_8)
                    .build())
                .build();

            var response = http.request(request, String.class);

            int statusCode = response.getStatus().getCode();
            if (statusCode != 200 && statusCode != 201) {
                throw new RuntimeException(
                    "Huawei IAM token request failed: HTTP " + statusCode + " — " + response.getBody()
                );
            }

            String tokenValue = response.getHeaders().firstValue("X-Subject-Token")
                .orElseThrow(() -> new RuntimeException(
                    "Huawei IAM responded with HTTP " + statusCode +
                        " but did not include an X-Subject-Token header"
                ));

            String expiresAtRaw = ConnectionUtils.parseExpiresAt(response.getBody());
            Instant expiresAt = expiresAtRaw != null ? Instant.parse(expiresAtRaw) : null;

            Token token = Token.builder()
                .tokenValue(EncryptedString.from(tokenValue, runContext))
                .expirationTime(expiresAt)
                .build();

            return Output.builder().token(token).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @NotNull
        @Schema(
            title = "The issued Huawei Cloud IAM token wrapper."
        )
        private final Token token;
    }

    @Builder
    @Getter
    public static class Token {
        @Schema(
            title = "The IAM token value (contents of the `X-Subject-Token` response header).",
            description = "Encrypted in outputs when the Kestra instance has output encryption configured. " +
                "Pass to downstream Huawei tasks as `securityToken`."
        )
        EncryptedString tokenValue;

        @Schema(
            title = "Token expiration time (UTC).",
            description = "Parsed from the `token.expires_at` field of the IAM response body. May be null if " +
                "IAM omits the field."
        )
        Instant expirationTime;
    }
}
