package io.kestra.plugin.huawei.mrs;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.mrs.v2.model.ChargeInfo;
import com.huaweicloud.sdk.mrs.v2.model.CreateClusterReqV2;
import com.huaweicloud.sdk.mrs.v2.model.CreateClusterRequest;
import com.huaweicloud.sdk.mrs.v2.model.NodeGroupV2;
import com.huaweicloud.sdk.mrs.v2.model.RunJobFlowCommand;
import com.huaweicloud.sdk.mrs.v2.model.RunJobFlowRequest;
import com.huaweicloud.sdk.mrs.v2.model.StepConfig;
import com.huaweicloud.sdk.mrs.v2.model.Volume;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.mrs.models.JobConfig;
import io.kestra.plugin.huawei.mrs.models.NodeGroupConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create a Huawei Cloud MRS cluster and submit job steps to it",
    description = """
        Creates a new MRS (MapReduce Service) cluster and, optionally, submits one or more job steps
        to run on it once it is ready — the Huawei Cloud equivalent of
        `io.kestra.plugin.aws.emr.CreateClusterAndSubmitSteps`.

        MRS cluster creation requires a richer set of properties than EMR: VPC/subnet placement,
        node group sizing per role, and either a manager admin password or a login key pair. Billing
        is always pay-per-use (`postPaid`) — prepaid clusters are not supported by this task.

        When `steps` is omitted, the cluster is created via MRS's `createCluster` API (cluster-only —
        that endpoint has no `steps` field). Jobs can then be submitted to the running cluster with
        the `SubmitJob` task. When `steps` is provided, the cluster and its steps are created together
        via MRS's `runJobFlow` API, which requires a non-empty `steps` field.

        Set `wait` to `true` (the default) to poll until the cluster reaches the `running` state, at
        which point step job IDs (if any steps were submitted) are best-effort resolved and returned.
        Set `wait` to `false` to return immediately after the cluster creation request is accepted —
        in that case `clusterState` and `jobIds` are left empty, since the cluster and its steps are
        still being provisioned asynchronously.

        The created cluster is a long-lived infrastructure resource: killing this task does not delete
        it. Use `DeleteCluster` to tear it down.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Create a 5-node analysis cluster (2 masters + 3 core) and submit a Spark job.",
            full = true,
            code = """
                id: mrs_create_cluster_and_submit_job
                namespace: company.team

                tasks:
                  - id: create_cluster
                    type: io.kestra.plugin.huawei.mrs.CreateClusterAndSubmitJob
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    clusterName: kestra-qa-cluster
                    clusterVersion: "MRS 3.5.0-LTS" # current LTS -> in-stock, current-gen node flavors
                    clusterType: ANALYSIS
                    availabilityZone: eu-west-101a # AZ id, not the region itself
                    vpcName: vpc-default
                    subnetName: subnet-default
                    components:
                      - Hadoop
                      - Spark # "Spark" on 3.5.x, NOT "Spark2x"
                      - Hive
                    nodeGroups:
                      - groupName: master_node_default_group
                        nodeNum: 2 # master group must have >= 2 nodes
                        nodeSize: c6.4xlarge.4.linux.bigdata # note the .linux.bigdata suffix
                        rootVolumeType: SAS
                        rootVolumeSize: 480
                        dataVolumeType: SAS
                        dataVolumeSize: 600 # data disk must be >= 480 GB
                        dataVolumeCount: 1
                      - groupName: core_node_analysis_group # "analysis" core group for ANALYSIS clusters
                        nodeNum: 3
                        nodeSize: c6.4xlarge.4.linux.bigdata
                        rootVolumeType: SAS
                        rootVolumeSize: 480
                        dataVolumeType: SAS
                        dataVolumeSize: 600
                        dataVolumeCount: 1
                    loginMode: PASSWORD
                    nodeRootPassword: "{{ secret('MRS_NODE_ROOT_PASSWORD') }}"
                    managerAdminPassword: "{{ secret('MRS_MANAGER_ADMIN_PASSWORD') }}"
                    steps:
                      - jobType: SPARK_SUBMIT
                        jobName: daily-aggregation
                        arguments:
                          - "--class"
                          - "com.example.DailyAggregation"
                          - "obs://my-bucket/jars/etl.jar"
                    maxDuration: PT30M
                """
        )
    }
)
public class CreateClusterAndSubmitJob extends AbstractMrs implements RunnableTask<CreateClusterAndSubmitJob.Output> {

    @Schema(title = "Cluster name")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> clusterName;

    @Schema(title = "MRS runtime version", description = "E.g. `MRS 3.2.0-LTS.3`. See the MRS console for versions available in your region.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> clusterVersion;

    @Schema(title = "Cluster type")
    @NotNull
    @PluginProperty(group = "main")
    private Property<ClusterType> clusterType;

    @Schema(title = "Hadoop ecosystem components to install", description = "E.g. `Hadoop`, `Spark2x`, `Hive`, `Tez`, `Flink`.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<String>> components;

    @Schema(
        title = "Job steps to submit once the cluster is ready",
        description = """
            Each step is submitted individually via MRS's job-execution API as soon as the cluster
            reaches the `running` state. Optional: when omitted, the task only creates the cluster
            (no jobs are queued) — use the `SubmitJob` task to submit jobs afterward.
            """
    )
    @PluginProperty(group = "main")
    private Property<List<JobConfig>> steps;

    @Schema(title = "Availability zone", description = "E.g. `eu-west-101a`.")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> availabilityZone;

    @Schema(title = "VPC name")
    @NotNull
    @PluginProperty(group = "connection")
    private Property<String> vpcName;

    @Schema(title = "Subnet ID", description = "Either `subnetId` or `subnetName` is required.")
    @PluginProperty(group = "connection")
    private Property<String> subnetId;

    @Schema(title = "Subnet name", description = "Either `subnetId` or `subnetName` is required.")
    @PluginProperty(group = "connection")
    private Property<String> subnetName;

    @Schema(
        title = "Security group ID",
        description = "When omitted, MRS creates a default security group for the cluster automatically."
    )
    @PluginProperty(group = "connection")
    private Property<String> securityGroupsId;

    @Schema(title = "Node groups", description = "At least a master and a core node group are typically required — see the MRS console for the groups a given `clusterVersion`/`clusterType` expects.")
    @NotNull
    @PluginProperty(group = "execution")
    private Property<List<NodeGroupConfig>> nodeGroups;

    @Schema(title = "Cluster authentication mode", description = "Defaults to `SIMPLE`.")
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<SafeMode> safeMode = Property.ofValue(SafeMode.SIMPLE);

    @Schema(title = "Node SSH login method")
    @NotNull
    @PluginProperty(group = "execution")
    private Property<LoginMode> loginMode;

    @Schema(title = "MRS Manager web console admin password", description = "Required regardless of `loginMode`.")
    @NotNull
    @ToString.Exclude
    @PluginProperty(group = "execution", secret = true)
    private Property<String> managerAdminPassword;

    @Schema(title = "Node root password", description = "Required when `loginMode` is `PASSWORD`.")
    @ToString.Exclude
    @PluginProperty(group = "execution", secret = true)
    private Property<String> nodeRootPassword;

    @Schema(title = "Node login key pair name", description = "Required when `loginMode` is `PUBLICKEY`. Must already exist in the target region.")
    @PluginProperty(group = "execution")
    private Property<String> nodeKeypairName;

    @Schema(
        title = "Delete the cluster automatically once all submitted steps complete",
        description = "Defaults to `false` (the cluster keeps running after its steps finish). Set to `true` for ephemeral, job-only clusters."
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Boolean> deleteWhenNoSteps = Property.ofValue(false);

    @Schema(title = "Enterprise project ID")
    @PluginProperty(group = "advanced")
    private Property<String> enterpriseProjectId;

    @Schema(title = "OBS URI to collect component logs to", description = "E.g. `obs://my-bucket/mrs-logs/`.")
    @PluginProperty(group = "advanced")
    private Property<String> logUri;

    @Schema(
        title = "Wait for the cluster to reach the `running` state",
        description = """
            When `true` (the default), polls cluster status until `running` (or a failure state) and
            best-effort resolves step job IDs once the cluster is ready. When `false`, returns
            immediately after the creation request is accepted, leaving `clusterState` and `jobIds`
            empty.
            """
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Boolean> wait = Property.ofValue(true);

    @Schema(title = "Maximum time to wait for the cluster to reach `running`", description = "ISO-8601 duration. Defaults to 30 minutes.")
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Duration> maxDuration = Property.ofValue(Duration.ofMinutes(30));

    @Schema(title = "Polling interval while waiting for the cluster", description = "ISO-8601 duration. Defaults to 15 seconds.")
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Duration> interval = Property.ofValue(Duration.ofSeconds(15));

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rClusterName = runContext.render(clusterName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("clusterName is required"));
        var rClusterVersion = runContext.render(clusterVersion).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("clusterVersion is required"));
        var rClusterType = runContext.render(clusterType).as(ClusterType.class)
            .orElseThrow(() -> new IllegalArgumentException("clusterType is required"));
        var rComponents = runContext.render(components).asList(String.class);
        if (rComponents.isEmpty()) {
            throw new IllegalArgumentException("components is required and must not be empty");
        }
        var rAvailabilityZone = runContext.render(availabilityZone).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("availabilityZone is required"));
        var rVpcName = runContext.render(vpcName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("vpcName is required"));
        var rSubnetId = runContext.render(subnetId).as(String.class).orElse(null);
        var rSubnetName = runContext.render(subnetName).as(String.class).orElse(null);
        if ((rSubnetId == null || rSubnetId.isBlank()) && (rSubnetName == null || rSubnetName.isBlank())) {
            throw new IllegalArgumentException("Either 'subnetId' or 'subnetName' is required");
        }
        var rSecurityGroupsId = runContext.render(securityGroupsId).as(String.class).orElse(null);
        var rSafeMode = runContext.render(safeMode).as(SafeMode.class).orElse(SafeMode.SIMPLE);
        var rLoginMode = runContext.render(loginMode).as(LoginMode.class)
            .orElseThrow(() -> new IllegalArgumentException("loginMode is required"));
        var rManagerAdminPassword = runContext.render(managerAdminPassword).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("managerAdminPassword is required"));
        var rNodeRootPassword = runContext.render(nodeRootPassword).as(String.class).orElse(null);
        var rNodeKeypairName = runContext.render(nodeKeypairName).as(String.class).orElse(null);
        if (rLoginMode == LoginMode.PASSWORD && (rNodeRootPassword == null || rNodeRootPassword.isBlank())) {
            throw new IllegalArgumentException("nodeRootPassword is required when loginMode is PASSWORD");
        }
        if (rLoginMode == LoginMode.PUBLICKEY && (rNodeKeypairName == null || rNodeKeypairName.isBlank())) {
            throw new IllegalArgumentException("nodeKeypairName is required when loginMode is PUBLICKEY");
        }
        var rDeleteWhenNoSteps = runContext.render(deleteWhenNoSteps).as(Boolean.class).orElse(false);
        var rEnterpriseProjectId = runContext.render(enterpriseProjectId).as(String.class).orElse(null);
        var rLogUri = runContext.render(logUri).as(String.class).orElse(null);
        var rWait = runContext.render(wait).as(Boolean.class).orElse(true);
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(Duration.ofMinutes(30));
        var rInterval = runContext.render(interval).as(Duration.class).orElse(Duration.ofSeconds(15));

        var rNodeGroups = runContext.render(nodeGroups).asList(NodeGroupConfig.class);
        if (rNodeGroups.isEmpty()) {
            throw new IllegalArgumentException("nodeGroups is required and must not be empty");
        }
        var nodeGroupsV2 = new ArrayList<NodeGroupV2>(rNodeGroups.size());
        for (var i = 0; i < rNodeGroups.size(); i++) {
            nodeGroupsV2.add(toNodeGroupV2(runContext, rNodeGroups.get(i), i));
        }

        var rSteps = runContext.render(steps).asList(JobConfig.class);
        var stepJobNames = new ArrayList<String>(rSteps.size());
        var stepConfigs = new ArrayList<StepConfig>(rSteps.size());
        for (var i = 0; i < rSteps.size(); i++) {
            var jobExecution = MrsService.toJobExecution(runContext, rSteps.get(i), "steps[" + i + "]");
            stepJobNames.add(jobExecution.getJobName());
            stepConfigs.add(new StepConfig().withJobExecution(jobExecution));
        }

        var rRegion = runContext.render(region).as(String.class).orElse(null);
        var client = client(runContext);
        var requestStart = System.currentTimeMillis();

        // The V2 runJobFlow API requires a non-empty `steps` field, so a cluster-only creation (no
        // steps) must go through the separate createCluster API instead, which has no `steps`/
        // `deleteWhenNoSteps` field at all.
        String clusterId;
        try {
            if (stepConfigs.isEmpty()) {
                var body = new CreateClusterReqV2()
                    .withClusterName(rClusterName)
                    .withClusterVersion(rClusterVersion)
                    .withClusterType(rClusterType.name())
                    .withRegion(rRegion)
                    .withAvailabilityZone(rAvailabilityZone)
                    .withVpcName(rVpcName)
                    .withComponents(String.join(",", rComponents))
                    .withNodeGroups(nodeGroupsV2)
                    .withSafeMode(rSafeMode.name())
                    .withLoginMode(rLoginMode.name())
                    .withManagerAdminPassword(rManagerAdminPassword)
                    // MRS clusters submitted through this task are always billed pay-per-use; prepaid
                    // billing is not exposed here since job-triggered clusters are, by nature, on-demand.
                    .withChargeInfo(new ChargeInfo().withChargeMode("postPaid"));

                applySharedOptionalFields(rSubnetId, rSubnetName, rSecurityGroupsId, rLoginMode,
                    rNodeRootPassword, rNodeKeypairName, rEnterpriseProjectId, rLogUri,
                    body::withSubnetId, body::withSubnetName, body::withSecurityGroupsId,
                    body::withAutoCreateDefaultSecurityGroup, body::withNodeRootPassword,
                    body::withNodeKeypairName, body::withEnterpriseProjectId, body::withLogUri);

                clusterId = client.createCluster(new CreateClusterRequest().withBody(body)).getClusterId();
            } else {
                var body = new RunJobFlowCommand()
                    .withClusterName(rClusterName)
                    .withClusterVersion(rClusterVersion)
                    .withClusterType(rClusterType.name())
                    .withRegion(rRegion)
                    .withAvailabilityZone(rAvailabilityZone)
                    .withVpcName(rVpcName)
                    .withComponents(String.join(",", rComponents))
                    .withNodeGroups(nodeGroupsV2)
                    .withSafeMode(rSafeMode.name())
                    .withLoginMode(rLoginMode.name())
                    .withManagerAdminPassword(rManagerAdminPassword)
                    .withDeleteWhenNoSteps(rDeleteWhenNoSteps)
                    .withSteps(stepConfigs)
                    // MRS clusters submitted through this task are always billed pay-per-use; prepaid
                    // billing is not exposed here since job-triggered clusters are, by nature, on-demand.
                    .withChargeInfo(new ChargeInfo().withChargeMode("postPaid"));

                applySharedOptionalFields(rSubnetId, rSubnetName, rSecurityGroupsId, rLoginMode,
                    rNodeRootPassword, rNodeKeypairName, rEnterpriseProjectId, rLogUri,
                    body::withSubnetId, body::withSubnetName, body::withSecurityGroupsId,
                    body::withAutoCreateDefaultSecurityGroup, body::withNodeRootPassword,
                    body::withNodeKeypairName, body::withEnterpriseProjectId, body::withLogUri);

                clusterId = client.runJobFlow(new RunJobFlowRequest().withBody(body)).getClusterId();
            }
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "Failed to create MRS cluster '" + rClusterName + "' (HTTP " + e.getHttpStatusCode() + "): " +
                e.getErrorMsg() + " — verify the VPC/subnet/security group and node flavors are valid for region '" +
                rRegion + "'.", e);
        } catch (SdkException e) {
            throw new IllegalStateException("Failed to create MRS cluster '" + rClusterName + "': " + e.getMessage(), e);
        }

        logger.info("Created MRS cluster '{}' (id={}), {} step(s) queued", rClusterName, clusterId, stepConfigs.size());

        if (!rWait) {
            return Output.builder().clusterId(clusterId).clusterState(null).jobIds(List.of()).build();
        }

        var cluster = MrsService.pollClusterUntilTerminal(clientV1(runContext), clusterId, rInterval, rMaxDuration, logger);
        logger.info("MRS cluster '{}' reached state '{}'", clusterId, cluster.getClusterState());

        var jobIds = stepJobNames.isEmpty()
            ? List.<String>of()
            : MrsService.resolveStepJobIds(client, clusterId, stepJobNames, requestStart, logger);

        return Output.builder().clusterId(clusterId).clusterState(cluster.getClusterState()).jobIds(jobIds).build();
    }

    // `RunJobFlowCommand` and `CreateClusterReqV2` expose identical fluent setters for every field
    // below, but the SDK gives them no common supertype/interface — bridging via method references
    // keeps the conditional logic in one place instead of duplicating it per request body type.
    private static void applySharedOptionalFields(
        String rSubnetId, String rSubnetName, String rSecurityGroupsId, LoginMode rLoginMode,
        String rNodeRootPassword, String rNodeKeypairName, String rEnterpriseProjectId, String rLogUri,
        Consumer<String> subnetIdSetter, Consumer<String> subnetNameSetter, Consumer<String> securityGroupsIdSetter,
        Consumer<Boolean> autoCreateDefaultSecurityGroupSetter, Consumer<String> nodeRootPasswordSetter,
        Consumer<String> nodeKeypairNameSetter, Consumer<String> enterpriseProjectIdSetter, Consumer<String> logUriSetter
    ) {
        if (rSubnetId != null && !rSubnetId.isBlank()) {
            subnetIdSetter.accept(rSubnetId);
        }
        if (rSubnetName != null && !rSubnetName.isBlank()) {
            subnetNameSetter.accept(rSubnetName);
        }
        if (rSecurityGroupsId != null && !rSecurityGroupsId.isBlank()) {
            securityGroupsIdSetter.accept(rSecurityGroupsId);
        } else {
            autoCreateDefaultSecurityGroupSetter.accept(true);
        }
        if (rLoginMode == LoginMode.PASSWORD) {
            nodeRootPasswordSetter.accept(rNodeRootPassword);
        } else {
            nodeKeypairNameSetter.accept(rNodeKeypairName);
        }
        if (rEnterpriseProjectId != null && !rEnterpriseProjectId.isBlank()) {
            enterpriseProjectIdSetter.accept(rEnterpriseProjectId);
        }
        if (rLogUri != null && !rLogUri.isBlank()) {
            logUriSetter.accept(rLogUri);
        }
    }

    // `nodeNum`/volume sizes/`dataVolumeCount` can't carry @Min/@Max directly on NodeGroupConfig:
    // Hibernate Validator has no ValueExtractor for Property<>, so those annotations blow up
    // flow-save-time bean validation with HV000030 (mirrors AbstractGeminiDb.renderedLimit). The
    // bounds are enforced here instead, at render time.
    private static final int MIN_DATA_VOLUME_COUNT = 1;
    private static final int MAX_DATA_VOLUME_COUNT = 20;

    private static NodeGroupV2 toNodeGroupV2(RunContext runContext, NodeGroupConfig config, int index) throws Exception {
        var rGroupName = runContext.render(config.getGroupName()).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("nodeGroups[" + index + "].groupName is required"));
        var rNodeNum = runContext.render(config.getNodeNum()).as(Integer.class)
            .orElseThrow(() -> new IllegalArgumentException("nodeGroups[" + index + "].nodeNum is required"));
        if (rNodeNum < 1) {
            throw new IllegalArgumentException(
                "nodeGroups[" + index + "].nodeNum must be >= 1 (was " + rNodeNum + ")");
        }
        var rNodeSize = runContext.render(config.getNodeSize()).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("nodeGroups[" + index + "].nodeSize is required"));
        var rRootVolumeType = runContext.render(config.getRootVolumeType()).as(String.class).orElse("SATA");
        var rRootVolumeSize = runContext.render(config.getRootVolumeSize()).as(Integer.class).orElse(40);
        if (rRootVolumeSize < 1) {
            throw new IllegalArgumentException(
                "nodeGroups[" + index + "].rootVolumeSize must be >= 1 (was " + rRootVolumeSize + ")");
        }
        var rDataVolumeType = runContext.render(config.getDataVolumeType()).as(String.class).orElse("SATA");
        var rDataVolumeSize = runContext.render(config.getDataVolumeSize()).as(Integer.class).orElse(100);
        if (rDataVolumeSize < 1) {
            throw new IllegalArgumentException(
                "nodeGroups[" + index + "].dataVolumeSize must be >= 1 (was " + rDataVolumeSize + ")");
        }
        var rDataVolumeCount = runContext.render(config.getDataVolumeCount()).as(Integer.class).orElse(1);
        if (rDataVolumeCount < MIN_DATA_VOLUME_COUNT || rDataVolumeCount > MAX_DATA_VOLUME_COUNT) {
            throw new IllegalArgumentException(
                "nodeGroups[" + index + "].dataVolumeCount must be between " + MIN_DATA_VOLUME_COUNT +
                " and " + MAX_DATA_VOLUME_COUNT + " (was " + rDataVolumeCount + ")");
        }

        return new NodeGroupV2()
            .withGroupName(rGroupName)
            .withNodeNum(rNodeNum)
            .withNodeSize(rNodeSize)
            .withRootVolume(new Volume().withType(rRootVolumeType).withSize(rRootVolumeSize))
            .withDataVolume(new Volume().withType(rDataVolumeType).withSize(rDataVolumeSize))
            .withDataVolumeCount(rDataVolumeCount);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "ID of the created cluster")
        private final String clusterId;

        @Schema(title = "Cluster state when the task returned", description = "`running` on success. `null` when `wait` is `false`.")
        private final String clusterState;

        @Schema(title = "Job IDs resolved for the submitted steps", description = "Best-effort; empty when no steps were submitted or `wait` is `false`.")
        private final List<String> jobIds;
    }
}
