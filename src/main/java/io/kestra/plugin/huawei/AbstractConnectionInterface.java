package io.kestra.plugin.huawei;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
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
            "signing requests with AK/SK. Typically populated from the output of a previous `GetToken` task " +
            "(`{{ outputs.get_token.token.tokenValue }}`). **Sensitive.**"
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
        description = "Region identifier such as `eu-west-101`, `ap-southeast-1`, or `cn-north-4`. Used to build " +
            "the default IAM endpoint when `iamEndpointOverride` is not set."
    )
    @PluginProperty(group = "connection")
    Property<String> getRegion();

    @Schema(
        title = "Override for the IAM endpoint URL.",
        description = "Full base URL to use instead of the region-derived default " +
            "(`https://iam.<region>.myhuaweicloud.com`). Useful for sovereign clouds such as `myhuaweicloud.eu` " +
            "or for routing through a private endpoint."
    )
    @PluginProperty(group = "advanced")
    Property<String> getIamEndpointOverride();

    default AbstractConnection.HuaweiClientConfig huaweiClientConfig(final RunContext runContext) throws IllegalVariableEvaluationException {
        return new AbstractConnection.HuaweiClientConfig(
            runContext.render(this.getAccessKeyId()).as(String.class).orElse(null),
            runContext.render(this.getSecretAccessKey()).as(String.class).orElse(null),
            runContext.render(this.getSecurityToken()).as(String.class).orElse(null),
            runContext.render(this.getProjectId()).as(String.class).orElse(null),
            runContext.render(this.getDomainId()).as(String.class).orElse(null),
            runContext.render(this.getRegion()).as(String.class).orElse(null),
            runContext.render(this.getIamEndpointOverride()).as(String.class).orElse(null)
        );
    }
}
