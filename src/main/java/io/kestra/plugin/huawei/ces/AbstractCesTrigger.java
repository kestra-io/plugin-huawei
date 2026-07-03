package io.kestra.plugin.huawei.ces;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.plugin.huawei.TemporaryCredentialsConfig;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Connection-aware base for CES triggers.
 *
 * <p>Triggers must extend {@link AbstractTrigger} and so cannot also extend {@link AbstractCes}
 * (Java is single-inheritance). This class mirrors {@code AbstractCes} on the trigger side: it owns
 * the shared connection and CES endpoint properties so concrete triggers inherit them instead of
 * re-declaring each one, exactly as {@code AbstractObsTrigger} does for OBS. The property schema is
 * supplied by {@link CesConnectionInterface}, just as for tasks.
 */
@SuperBuilder
@ToString(exclude = {"accessKeyId", "secretAccessKey", "securityToken", "temporaryCredentials"})
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractCesTrigger extends AbstractTrigger implements CesConnectionInterface {

    protected Property<String> accessKeyId;
    protected Property<String> secretAccessKey;
    protected Property<String> securityToken;
    protected Property<String> projectId;
    protected Property<String> domainId;
    protected Property<String> region;
    protected Property<TemporaryCredentialsConfig> temporaryCredentials;
    protected Property<String> endpointOverride;
    protected Property<String> endpointSuffix;
}
