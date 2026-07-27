package io.kestra.plugin.huawei.mrs;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.mrs.v1.model.DeleteClusterRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
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
@Schema(
    title = "Delete a Huawei Cloud MRS cluster",
    description = """
        Deletes an MRS cluster by ID — the Huawei Cloud equivalent of
        `io.kestra.plugin.aws.emr.DeleteCluster`. Deletion is asynchronous: the cluster transitions to
        a `terminating` state and is removed shortly after this task returns.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: mrs_delete_cluster
                namespace: company.team

                tasks:
                  - id: delete_cluster
                    type: io.kestra.plugin.huawei.mrs.DeleteCluster
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    clusterId: "{{ outputs.create_cluster.clusterId }}"
                """
        )
    }
)
public class DeleteCluster extends AbstractMrs implements RunnableTask<DeleteCluster.Output> {

    @Schema(title = "ID of the cluster to delete")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> clusterId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rClusterId = runContext.render(clusterId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("clusterId is required"));

        var client = clientV1(runContext);
        try {
            client.deleteCluster(new DeleteClusterRequest().withClusterId(rClusterId));
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "Failed to delete MRS cluster '" + rClusterId + "' (HTTP " + e.getHttpStatusCode() + "): " +
                e.getErrorMsg() + " — verify the cluster ID exists and is not already being deleted.", e);
        } catch (SdkException e) {
            throw new IllegalStateException("Failed to delete MRS cluster '" + rClusterId + "': " + e.getMessage(), e);
        }

        runContext.logger().info("MRS cluster '{}' deletion requested", rClusterId);

        return Output.builder().clusterId(rClusterId).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "ID of the deleted cluster")
        private final String clusterId;
    }
}
