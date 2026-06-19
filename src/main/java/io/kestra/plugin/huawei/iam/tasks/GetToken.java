package io.kestra.plugin.huawei.iam.tasks;

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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Exchange a Huawei Cloud IAM token for temporary STS credentials.",
    description = """
        Calls the Huawei IAM STS API (`POST /v3.0/OS-CREDENTIAL/securitytokens`) to obtain
        short-lived temporary credentials (temporary AK, SK, and security token) from an existing
        IAM token.

        The returned `accessKeyId`, `secretAccessKey`, and `securityToken` can be passed directly
        to downstream Huawei Cloud tasks (OBS, DMS, …) in place of long-lived AK/SK credentials.
        Temporary credentials expire at `expirationTime` — refresh before that deadline.

        The input `token` is the `X-Auth-Token` previously obtained from Huawei IAM (e.g. via
        `keystoneCreateUserTokenByPassword`). It is sent both as an HTTP header for authentication
        and in the request body as the identity token to exchange.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Exchange an IAM token for temporary credentials and use them for an OBS upload.",
            full = true,
            code = """
                id: iam_get_temp_credentials
                namespace: company.team

                tasks:
                  - id: get_temp_creds
                    type: io.kestra.plugin.huawei.iam.tasks.GetToken
                    region: eu-west-101
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
        ),
        @Example(
            title = "Obtain the minimum-lifetime temporary credentials (900 seconds).",
            full = true,
            code = """
                id: iam_short_lived_creds
                namespace: company.team

                tasks:
                  - id: get_temp_creds
                    type: io.kestra.plugin.huawei.iam.tasks.GetToken
                    region: eu-west-101
                    token: "{{ secret('HUAWEI_IAM_TOKEN') }}"
                """
        )
    }
)
public class GetToken extends Task implements RunnableTask<GetToken.Output> {

    @Schema(
        title = "Huawei Cloud region.",
        description = "Region identifier such as `eu-west-101`, `ap-southeast-1`, or `cn-north-4`. " +
            "Used to resolve the IAM endpoint URL."
    )
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> region;

    @Schema(
        title = "IAM token to exchange for temporary STS credentials.",
        description = """
            An existing Huawei Cloud IAM token (`X-Auth-Token`) to exchange. Obtain one via the
            IAM `keystoneCreateUserTokenByPassword` API or equivalent. The token is sent both as
            the `X-Auth-Token` request header (for client authentication) and in the request body
            as the identity to exchange. **Sensitive — always provide via `{{ secret('NAME') }}`.**
            """
    )
    @NotNull
    @PluginProperty(group = "main", secret = true)
    private Property<String> token;

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

    @Override
    public Output run(RunContext runContext) throws Exception {
        return run(runContext, null);
    }

    Output run(RunContext runContext, String endpointOverride) throws Exception {
        var rRegion = runContext.render(region).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "region is required to resolve the IAM endpoint — set the 'region' property (e.g. eu-west-101)"));
        var rToken = runContext.render(token).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "token is required — provide an existing IAM X-Auth-Token to exchange for temporary credentials"));
        var rDuration = runContext.render(durationSeconds).as(Integer.class).orElse(900);

        var client = endpointOverride != null
            ? ConnectionUtils.iamClientWithToken(rToken, rRegion, endpointOverride)
            : ConnectionUtils.iamClientWithToken(rToken, rRegion);

        var response = client.createTemporaryAccessKeyByToken(buildRequest(rToken, rDuration));

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

    private static CreateTemporaryAccessKeyByTokenRequest buildRequest(String tokenValue, int duration) {
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
