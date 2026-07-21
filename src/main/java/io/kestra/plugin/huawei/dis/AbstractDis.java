package io.kestra.plugin.huawei.dis;

import com.huaweicloud.sdk.dis.v2.DisClient;
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
public abstract class AbstractDis extends AbstractConnection implements DisConnectionInterface {

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
