package io.kestra.plugin.huawei.obs;

import com.obs.services.ObsClient;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractObs extends AbstractConnection implements AbstractObsInterface {

    protected Property<String> endpointOverride;
    protected Property<Boolean> pathStyleAccess;
    protected Property<AuthType> authType;

    /**
     * Builds a configured {@link ObsClient} from the rendered task properties.
     *
     * <p>OBS uses AK/SK signing — the SDK does not support IAM token auth for object operations,
     * so both {@code accessKeyId} and {@code secretAccessKey} must be present.
     */
    protected ObsClient client(RunContext runContext) throws IllegalVariableEvaluationException {
        return ObsService.buildClient(
            huaweiClientConfig(runContext),
            runContext.render(endpointOverride).as(String.class).orElse(null),
            runContext.render(pathStyleAccess).as(Boolean.class).orElse(false),
            runContext.render(authType).as(AuthType.class).orElse(AuthType.OBS)
        );
    }
}
