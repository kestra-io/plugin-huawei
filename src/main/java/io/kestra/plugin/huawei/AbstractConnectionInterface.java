package io.kestra.plugin.huawei;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;

public interface AbstractConnectionInterface {

    @Schema(
        title = "Access Key (AK) used to authenticate with Huawei Cloud.",
        description = "Huawei Cloud access key used together with `secretAccessKey` to sign API requests. " +
            "Required for AK/SK-based authentication; not required when " +
            "providing a pre-obtained `securityToken`. **Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @PluginProperty(group = "connection", secret = true)
    Property<String> getAccessKeyId();

    @Schema(
        title = "Secret Key (SK) used to authenticate with Huawei Cloud.",
        description = "Huawei Cloud secret key paired with `accessKeyId`. " +
            "Required for AK/SK-based authentication. **Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @PluginProperty(group = "connection", secret = true)
    Property<String> getSecretAccessKey();

    @Schema(
        title = "Pre-obtained Huawei Cloud IAM token used as bearer credential for downstream API calls.",
        description = "When set, downstream Huawei tasks send this value in the `X-Auth-Token` header instead of " +
            "signing requests with AK/SK. **Sensitive.**"
    )
    @PluginProperty(group = "connection", secret = true)
    Property<String> getSecurityToken();

    @Schema(
        title = "Huawei Cloud Project ID.",
        description = "Identifies the region-scoped project against which most regional services authenticate. " +
            "Mutually exclusive with `domainId` for global services such as IAM."
    )
    @PluginProperty(group = "connection")
    Property<String> getProjectId();

    @Schema(
        title = "Huawei Cloud Account Domain ID.",
        description = "Identifies the Huawei Cloud account (domain). Required when authenticating against global " +
            "services such as IAM, or when requesting a domain-scoped IAM token."
    )
    @PluginProperty(group = "connection")
    Property<String> getDomainId();

    @Schema(
        title = "Huawei Cloud region.",
        description = "Region identifier such as `eu-west-101`, `ap-southeast-1`, or `cn-north-4`."
    )
    @PluginProperty(group = "connection")
    Property<String> getRegion();

    @Schema(
        title = "Inline IAM credential exchange.",
        description = """
            When set, the connection layer calls the Huawei IAM STS API once per task execution and
            uses the returned temporary AK/SK + security token instead of the static `accessKeyId`
            and `secretAccessKey` properties.

            Configure once via `pluginDefaults` to apply transparently to every task in a namespace
            without per-task credential wiring:

            ```yaml
            pluginDefaults:
              - type: io.kestra.plugin.huawei.obs
                values:
                  region: eu-west-101
                  temporaryCredentials:
                    authMethod: PASSWORD
                    username: my-iam-user
                    password: "{{ secret('HUAWEI_IAM_PASSWORD') }}"
                    domainName: my-account-domain
                    durationSeconds: 3600
            ```

            **Long-running tasks:** the exchange runs once at execution start. For `RealtimeTrigger`
            or long-running `Consume` tasks that outlive `durationSeconds`, credentials will expire
            mid-run. Use long-lived AK/SK properties or refresh externally in that case.
            """
    )
    @PluginProperty(group = "connection")
    Property<TemporaryCredentialsConfig> getTemporaryCredentials();

    default AbstractConnection.HuaweiClientConfig huaweiClientConfig(final RunContext runContext) throws Exception {
        return huaweiClientConfig(runContext, null);
    }

    /**
     * Builds the client config, optionally overriding the IAM endpoint used for the
     * {@code temporaryCredentials} exchange. The override is {@code null} in production and
     * points at a WireMock server during tests.
     */
    default AbstractConnection.HuaweiClientConfig huaweiClientConfig(
        final RunContext runContext,
        final String iamEndpointOverride
    ) throws Exception {
        var rRegion = runContext.render(this.getRegion()).as(String.class).orElse(null);

        var tempCredsProperty = this.getTemporaryCredentials();
        if (tempCredsProperty != null) {
            var tempCredsConfig = runContext.render(tempCredsProperty).as(TemporaryCredentialsConfig.class).orElse(null);
            if (tempCredsConfig != null) {
                var temp = ConnectionUtils.exchangeForTemporaryCredentials(
                    runContext, tempCredsConfig, rRegion, iamEndpointOverride);
                return new AbstractConnection.HuaweiClientConfig(
                    temp.accessKeyId(),
                    temp.secretAccessKey(),
                    temp.securityToken(),
                    runContext.render(this.getProjectId()).as(String.class).orElse(null),
                    runContext.render(this.getDomainId()).as(String.class).orElse(null),
                    rRegion
                );
            }
        }

        return new AbstractConnection.HuaweiClientConfig(
            runContext.render(this.getAccessKeyId()).as(String.class).orElse(null),
            runContext.render(this.getSecretAccessKey()).as(String.class).orElse(null),
            runContext.render(this.getSecurityToken()).as(String.class).orElse(null),
            runContext.render(this.getProjectId()).as(String.class).orElse(null),
            runContext.render(this.getDomainId()).as(String.class).orElse(null),
            rRegion
        );
    }
}
