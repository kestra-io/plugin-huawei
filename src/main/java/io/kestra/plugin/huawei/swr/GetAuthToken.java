package io.kestra.plugin.huawei.swr;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.swr.v2.model.AuthInfo;
import com.huaweicloud.sdk.swr.v2.model.CreateSecretRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.EncryptedString;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get a short-lived SWR (Software Repository for Container) registry auth token",
    description = """
        Fetches a temporary registry credential from
        [SWR (Software Repository for Container)](https://www.huaweicloud.com/product/swr.html), the
        Huawei Cloud equivalent of `io.kestra.plugin.aws.ecr.GetAuthToken`. Use the returned
        `username`/`password` to run `docker login` (or any OCI-compliant client login) against
        `registry` before pushing or pulling images.

        The credential is issued with a fixed validity period set by SWR itself — this task has no
        control over its lifetime (unlike ECR's authorization token, SWR's `createSecret` API accepts
        no duration parameter).

        Authentication uses AK/SK request signing. Provide `accessKeyId` and `secretAccessKey` via
        `{{ secret('NAME') }}`, or configure `temporaryCredentials` for inline IAM credential exchange.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Fetch an SWR auth token and use it to log in to the registry with the Docker CLI.",
            full = true,
            code = """
                id: swr_get_auth_token
                namespace: company.team

                tasks:
                  - id: get_auth_token
                    type: io.kestra.plugin.huawei.swr.GetAuthToken
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101

                  - id: docker_login
                    type: io.kestra.plugin.scripts.shell.Commands
                    taskRunner:
                      type: io.kestra.plugin.core.runner.Process
                    commands:
                      - docker login -u {{ outputs.get_auth_token.username }} -p {{ outputs.get_auth_token.password.value }} {{ outputs.get_auth_token.registry }}
                """
        )
    }
)
public class GetAuthToken extends AbstractSwr implements RunnableTask<GetAuthToken.Output> {

    // `docker login -u <username> -p <password> <registry>`, e.g.
    // "docker login -u eu-west-101@ABCDEF -p eyJhbGci... swr.eu-west-101.myhuaweicloud.com".
    private static final Pattern DOCKERLOGIN_PATTERN =
        Pattern.compile("-u\\s+(\\S+)\\s+-p\\s+(\\S+)\\s+(\\S+)");

    @Schema(
        title = "SWR project name",
        description = "The Huawei Cloud project name to issue the credential for. Optional; defaults to `region` " +
            "when omitted, which matches the project name for standard (non-enterprise multi-project) accounts."
    )
    @PluginProperty(group = "connection")
    private Property<String> projectName;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rRegion = runContext.render(region).as(String.class).orElse(null);
        var rProjectName = runContext.render(projectName).as(String.class).orElse(rRegion);

        var request = new CreateSecretRequest();
        if (rProjectName != null && !rProjectName.isBlank()) {
            request.withProjectname(rProjectName);
        }

        var client = client(runContext);

        try {
            var response = client.createSecret(request);
            var credential = extractCredential(response.getAuths(), response.getXSwrDockerlogin(), rRegion);

            logger.info("Obtained SWR auth token for registry '{}', expiring at '{}'",
                credential.registry(), response.getXSwrExpireat());

            return Output.builder()
                .username(credential.username())
                .password(EncryptedString.from(credential.password(), runContext))
                .registry(credential.registry())
                .expiry(response.getXSwrExpireat())
                .build();
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "SWR createSecret failed (HTTP " + e.getHttpStatusCode() + "): " + e.getErrorMsg() +
                " — verify that the AK/SK has 'SWR Administrator' (or equivalent) permission for region '" +
                rRegion + "'.", e);
        } catch (SdkException e) {
            throw new IllegalStateException("SWR SDK error obtaining auth token: " + e.getMessage(), e);
        }
    }

    /**
     * Prefers decoding {@code auths.<host>.auth} (base64 {@code username:password}) over parsing the
     * human-readable {@code xSwrDockerlogin} string — the map is structured data, the docker-login
     * string is a shell command SWR assembles for display and more brittle to parse. Falls back to the
     * docker-login string only when the map comes back empty.
     */
    private static Credential extractCredential(Map<String, AuthInfo> auths, String xSwrDockerlogin, String region) {
        if (auths != null && !auths.isEmpty()) {
            var entry = auths.entrySet().iterator().next();
            var registry = entry.getKey();
            var authValue = entry.getValue() == null ? null : entry.getValue().getAuth();
            if (authValue == null || authValue.isBlank()) {
                throw new IllegalArgumentException(
                    "SWR createSecret returned an entry for registry '" + registry + "' with no 'auth' value — " +
                    "verify AK/SK and SWR access, or retry the request.");
            }

            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(authValue));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "SWR createSecret returned an 'auth' value for registry '" + registry +
                    "' that could not be base64-decoded — this is unexpected; retry the request or contact " +
                    "Huawei Cloud support.", e);
            }

            var separator = decoded.indexOf(':');
            if (separator < 0) {
                throw new IllegalArgumentException(
                    "SWR createSecret returned a decoded 'auth' value for registry '" + registry +
                    "' with no ':' separating username and password — this is unexpected; retry the request.");
            }
            return new Credential(decoded.substring(0, separator), decoded.substring(separator + 1), registry);
        }

        if (xSwrDockerlogin != null && !xSwrDockerlogin.isBlank()) {
            var matcher = DOCKERLOGIN_PATTERN.matcher(xSwrDockerlogin);
            if (matcher.find()) {
                return new Credential(matcher.group(1), matcher.group(2), matcher.group(3));
            }
            // Deliberately does not echo `xSwrDockerlogin` in the message: it embeds the plaintext
            // password ("docker login -u ... -p <password> <registry>"), which would otherwise leak
            // into the Kestra UI/logs via this exception.
            throw new IllegalArgumentException(
                "SWR createSecret returned an 'X-Swr-Dockerlogin' header that could not be parsed — " +
                "expected format 'docker login -u <user> -p <password> <registry>'.");
        }

        throw new IllegalArgumentException(
            "SWR createSecret returned no credentials for region '" + region + "' — " +
            "verify AK/SK and that the account has SWR access.");
    }

    private record Credential(String username, String password, String registry) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Registry username", description = "Format: `<region>@<accessKeyId>`.")
        private final String username;

        @Schema(title = "Registry password", description = "Short-lived credential; encrypted at rest.")
        private final EncryptedString password;

        @Schema(title = "Registry host", description = "Format: `swr.<region>.myhuaweicloud.com` (or the sovereign-cloud equivalent).")
        private final String registry;

        @Schema(title = "Credential expiry timestamp", description = "As returned by SWR in the `X-Swr-Expireat` response header.")
        private final String expiry;
    }
}
