package io.kestra.plugin.huawei.obs;

import com.obs.services.ObsClient;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnectionInterface;
import io.kestra.plugin.huawei.TemporaryCredentialsConfig;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Connection-aware base for OBS triggers.
 *
 * <p>Triggers must extend {@link AbstractTrigger} and so cannot also extend {@link AbstractObs}
 * (Java is single-inheritance). This class mirrors {@code AbstractObs} on the trigger side: it owns
 * the shared connection and OBS-advanced properties and exposes the same {@link #client(RunContext)}
 * factory, so concrete triggers inherit them instead of re-declaring each one. The property schema is
 * supplied by {@link AbstractConnectionInterface} and {@link AbstractObsInterface}, exactly as for tasks.
 */
@SuperBuilder
@ToString(exclude = {"accessKeyId", "secretAccessKey", "securityToken"})
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractObsTrigger extends AbstractTrigger
    implements AbstractConnectionInterface, AbstractObsInterface {

    protected Property<String> accessKeyId;
    protected Property<String> secretAccessKey;
    protected Property<String> securityToken;
    protected Property<String> projectId;
    protected Property<String> domainId;
    protected Property<String> region;
    protected Property<TemporaryCredentialsConfig> temporaryCredentials;

    protected Property<String> endpointOverride;
    protected Property<Boolean> pathStyleAccess;
    protected Property<AuthType> authType;

    @Builder.Default
    protected Property<String> endpointSuffix = Property.ofValue("myhuaweicloud.com");

    /**
     * Builds a configured {@link ObsClient} from the rendered trigger properties. Mirrors
     * {@link AbstractObs#client(RunContext)} so trigger and task share one client-creation path.
     *
     * <p>The returned client owns an HTTP connection pool and must be closed by the caller — always
     * wrap it in a try-with-resources.
     */
    protected ObsClient client(RunContext runContext) throws Exception {
        return ObsService.buildClient(
            huaweiClientConfig(runContext),
            runContext.render(endpointOverride).as(String.class).orElse(null),
            runContext.render(pathStyleAccess).as(Boolean.class).orElse(false),
            runContext.render(authType).as(AuthType.class).orElse(AuthType.OBS),
            runContext.render(endpointSuffix).as(String.class).orElse("myhuaweicloud.com")
        );
    }
}
