package io.kestra.plugin.huawei.dis;

import com.huaweicloud.sdk.dis.v2.DisClient;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.TemporaryCredentialsConfig;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Connection-aware base for DIS triggers.
 *
 * <p>Triggers must extend {@link AbstractTrigger} and so cannot also extend {@link AbstractDis}
 * (Java is single-inheritance). This class mirrors {@code AbstractDis} on the trigger side: it owns
 * the shared connection and DIS endpoint properties, plus the same {@link #client(RunContext)}
 * factory, so both {@code Trigger} and {@code RealtimeTrigger} inherit them instead of re-declaring
 * each one — exactly as {@code AbstractCesTrigger}/{@code AbstractObsTrigger} do for their services.
 */
@SuperBuilder
@ToString(exclude = {"accessKeyId", "secretAccessKey", "securityToken", "temporaryCredentials"})
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractDisTrigger extends AbstractTrigger implements DisConnectionInterface {

    protected Property<String> accessKeyId;
    protected Property<String> secretAccessKey;
    protected Property<String> securityToken;
    protected Property<String> projectId;
    protected Property<String> domainId;
    protected Property<String> region;
    protected Property<TemporaryCredentialsConfig> temporaryCredentials;
    protected Property<String> endpointOverride;
    protected Property<String> endpointSuffix;

    protected DisClient client(RunContext runContext) throws Exception {
        var config = huaweiClientConfig(runContext);
        var rOverride = runContext.render(endpointOverride).as(String.class).orElse(null);
        var rRegion = runContext.render(region).as(String.class).orElse(null);
        var rSuffix = runContext.render(endpointSuffix).as(String.class).orElse(null);

        return DisService.buildClient(config, rOverride, rRegion, rSuffix);
    }
}
