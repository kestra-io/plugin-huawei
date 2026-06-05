package io.kestra.plugin.huawei;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import jakarta.annotation.Nullable;
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
public abstract class AbstractConnection extends Task implements AbstractConnectionInterface {

    protected Property<String> accessKeyId;
    protected Property<String> secretAccessKey;
    protected Property<String> securityToken;

    protected Property<String> projectId;
    protected Property<String> domainId;

    protected Property<String> region;

    /**
     * Snapshot of all connection-level Huawei properties after templating has been rendered.
     */
    public record HuaweiClientConfig(
        @Nullable String accessKeyId,
        @Nullable String secretAccessKey,
        @Nullable String securityToken,
        @Nullable String projectId,
        @Nullable String domainId,
        @Nullable String region
    ) {
    }
}
