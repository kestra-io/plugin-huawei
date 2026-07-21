package io.kestra.plugin.huawei.rfs;

import com.huaweicloud.sdk.aos.v1.model.GetStackMetadataResponse;
import com.huaweicloud.sdk.aos.v1.model.VarsStructure;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create or update a Huawei Cloud RFS stack",
    description = """
        Creates the stack if it does not exist, otherwise deploys an update — the Huawei Cloud
        Resource Formation Service (RFS) equivalent of `io.kestra.plugin.aws.cloudformation.Create`.

        RFS deploys Terraform/HCL templates (not CloudFormation-style JSON/YAML). Provide exactly one
        template source (`templateBody` inline, or `templateUri` pointing at an OBS-hosted template),
        and at most one variables source (`vars`, `varsBody`, or `varsUri`).

        Both the create and the deploy operations are asynchronous on Huawei's side: this task submits
        the request, then — when `wait` is `true` (the default) — polls `getStackMetadata` until the
        deployment reaches a terminal state. On success (`DEPLOYMENT_COMPLETE`), the stack's declared
        outputs are fetched and returned; sensitive outputs come back from RFS as the literal string
        `<sensitive>`, never the real value.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Create an OBS bucket via an inline Terraform template and wait for completion",
            code = """
                id: rfs_create_stack
                namespace: company.team

                tasks:
                  - id: create_bucket_stack
                    type: io.kestra.plugin.huawei.rfs.Create
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    stackName: my-bucket-stack
                    templateBody: |
                      terraform {
                        required_providers {
                          huaweicloud = {
                            source = "huaweicloud/huaweicloud"
                          }
                        }
                      }

                      variable "bucket_name" {
                        type = string
                      }

                      resource "huaweicloud_obs_bucket" "this" {
                        bucket = var.bucket_name
                        acl    = "private"
                      }

                      output "bucket_name" {
                        value = huaweicloud_obs_bucket.this.bucket
                      }
                    vars:
                      bucket_name: kestra-rfs-demo-bucket
                """
        ),
        @Example(
            full = true,
            title = "Deploy from an OBS-hosted template without waiting for completion",
            code = """
                id: rfs_create_stack_fire_and_forget
                namespace: company.team

                tasks:
                  - id: deploy_stack
                    type: io.kestra.plugin.huawei.rfs.Create
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    stackName: my-network-stack
                    templateUri: "obs://my-templates-bucket/network/main.tf"
                    varsUri: "obs://my-templates-bucket/network/prod.tfvars"
                    wait: false
                """
        )
    }
)
public class Create extends AbstractRfs implements RunnableTask<Create.Output> {

    @Schema(
        title = "Inline Terraform/HCL template",
        description = "The stack's Terraform configuration as a string. Exactly one of `templateBody` or `templateUri` must be set."
    )
    @PluginProperty(group = "main")
    private Property<String> templateBody;

    @Schema(
        title = "OBS URI of the Terraform/HCL template",
        description = "An `obs://bucket/key` (or signed HTTPS) URI RFS downloads the template from. Exactly one of `templateBody` or `templateUri` must be set."
    )
    @PluginProperty(group = "main")
    private Property<String> templateUri;

    @Schema(
        title = "Terraform variables",
        description = """
            Key-value map of Terraform variable values, sent as RFS `vars_structure` entries (string
            values only). Supports at most 100 entries. At most one of `vars`, `varsBody`, or
            `varsUri` may be set.
            """
    )
    @PluginProperty(group = "main")
    private Property<Map<String, String>> vars;

    @Schema(
        title = "Inline Terraform tfvars content",
        description = "Raw `.tfvars`-formatted content. At most one of `vars`, `varsBody`, or `varsUri` may be set."
    )
    @PluginProperty(group = "advanced")
    private Property<String> varsBody;

    @Schema(
        title = "OBS URI of a Terraform tfvars file",
        description = "An `obs://bucket/key` URI RFS downloads a `.tfvars` file from. At most one of `vars`, `varsBody`, or `varsUri` may be set."
    )
    @PluginProperty(group = "advanced")
    private Property<String> varsUri;

    @Schema(title = "Stack description")
    @PluginProperty(group = "main")
    private Property<String> stackDescription;

    @Builder.Default
    @Schema(
        title = "Enable deletion protection",
        description = "When `true`, the stack cannot be deleted until protection is disabled. Defaults to `false`."
    )
    @PluginProperty(group = "reliability")
    private Property<Boolean> enableDeletionProtection = Property.ofValue(false);

    @Builder.Default
    @Schema(
        title = "Enable automatic rollback on deployment failure",
        description = "When `true` (the default), RFS automatically rolls back to the last known-good state if the deployment fails."
    )
    @PluginProperty(group = "reliability")
    private Property<Boolean> enableAutoRollback = Property.ofValue(true);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rStackName = runContext.render(stackName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("'stackName' is required"));

        var rTemplateBody = runContext.render(templateBody).as(String.class).orElse(null);
        var rTemplateUri = runContext.render(templateUri).as(String.class).orElse(null);
        validateExactlyOneTemplateSource(rTemplateBody, rTemplateUri);

        var rVars = runContext.render(vars).asMap(String.class, String.class);
        var rVarsBody = runContext.render(varsBody).as(String.class).orElse(null);
        var rVarsUri = runContext.render(varsUri).as(String.class).orElse(null);
        validateAtMostOneVarsSource(rVars, rVarsBody, rVarsUri);
        var varsStructure = toVarsStructure(rVars);

        var rDescription = runContext.render(stackDescription).as(String.class).orElse(null);
        var rEnableDeletionProtection = runContext.render(enableDeletionProtection).as(Boolean.class).orElse(false);
        var rEnableAutoRollback = runContext.render(enableAutoRollback).as(Boolean.class).orElse(true);
        var rWait = runContext.render(wait).as(Boolean.class).orElse(true);
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(Duration.ofHours(1));
        var rInterval = runContext.render(interval).as(Duration.class).orElse(Duration.ofSeconds(5));

        var client = client(runContext);
        var logger = runContext.logger();

        var existing = RfsService.probe(client, rStackName);
        String stackId;
        String deploymentId;

        if (existing == null) {
            logger.info("RFS stack '{}' does not exist — creating.", rStackName);
            var response = RfsService.create(
                client, rStackName, rTemplateBody, rTemplateUri, varsStructure, rVarsBody, rVarsUri,
                rDescription, rEnableDeletionProtection, rEnableAutoRollback);
            stackId = response.getStackId();
            deploymentId = response.getDeploymentId();
        } else {
            logger.info("RFS stack '{}' already exists (status={}) — deploying an update.", rStackName, existing.getStatus());
            stackId = existing.getStackId();
            var response = RfsService.deploy(client, rStackName, rTemplateBody, rTemplateUri, varsStructure, rVarsBody, rVarsUri);
            deploymentId = response.getDeploymentId();
        }

        if (!rWait) {
            logger.info("RFS stack '{}' deployment '{}' submitted; not waiting for completion.", rStackName, deploymentId);
            return Output.builder()
                .stackId(stackId)
                .stackName(rStackName)
                .deploymentId(deploymentId)
                .status(null)
                .outputs(Map.of())
                .build();
        }

        var terminal = RfsService.pollUntilDeployTerminal(client, rStackName, rInterval, rMaxDuration, logger);

        if (terminal.getStatus() != GetStackMetadataResponse.StatusEnum.DEPLOYMENT_COMPLETE) {
            throw new IllegalStateException(
                "RFS stack '" + rStackName + "' deployment '" + deploymentId + "' finished with status '" + terminal.getStatus() + "'" +
                (terminal.getStatusMessage() != null ? ": " + terminal.getStatusMessage() : "") +
                " — check the RFS console deployment history for details.");
        }

        logger.info("RFS stack '{}' deployment '{}' completed successfully.", rStackName, deploymentId);

        var outputs = RfsService.listOutputs(client, rStackName);

        return Output.builder()
            .stackId(stackId)
            .stackName(rStackName)
            .deploymentId(deploymentId)
            .status(terminal.getStatus().getValue())
            .outputs(outputs)
            .build();
    }

    private static void validateExactlyOneTemplateSource(String rTemplateBody, String rTemplateUri) {
        var count = (isNotBlank(rTemplateBody) ? 1 : 0) + (isNotBlank(rTemplateUri) ? 1 : 0);
        if (count != 1) {
            throw new IllegalArgumentException(
                "Exactly one of 'templateBody' or 'templateUri' must be set (found " + count + ") — " +
                "provide the Terraform/HCL template inline via 'templateBody', or reference an OBS-hosted " +
                "template via 'templateUri'.");
        }
    }

    private static void validateAtMostOneVarsSource(Map<String, String> rVars, String rVarsBody, String rVarsUri) {
        var count = (rVars != null && !rVars.isEmpty() ? 1 : 0) + (isNotBlank(rVarsBody) ? 1 : 0) + (isNotBlank(rVarsUri) ? 1 : 0);
        if (count > 1) {
            throw new IllegalArgumentException(
                "At most one of 'vars', 'varsBody', or 'varsUri' may be set (found " + count + ") — " +
                "choose a single source for the stack's Terraform variables.");
        }
    }

    private static List<VarsStructure> toVarsStructure(Map<String, String> rVars) {
        if (rVars == null || rVars.isEmpty()) {
            return null;
        }
        if (rVars.size() > 100) {
            throw new IllegalArgumentException(
                "'vars' supports at most 100 entries (found " + rVars.size() + ") — " +
                "use 'varsBody' or 'varsUri' for larger variable sets.");
        }
        return rVars.entrySet().stream()
            .map(e -> new VarsStructure().withVarKey(e.getKey()).withVarValue(e.getValue()))
            .toList();
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Stack ID", description = "Unique identifier assigned by RFS.")
        private final String stackId;

        @Schema(title = "Stack name")
        private final String stackName;

        @Schema(title = "Deployment ID", description = "Identifier of the create/deploy operation that was submitted.")
        private final String deploymentId;

        @Schema(
            title = "Terminal deployment status",
            description = "`DEPLOYMENT_COMPLETE` on success. `null` when `wait` is `false`, since the deployment may still be in progress."
        )
        private final String status;

        @Schema(
            title = "Stack outputs",
            description = """
                Outputs declared by the deployed Terraform template, keyed by output name. Empty when
                `wait` is `false`. RFS returns sensitive outputs as the literal string `<sensitive>`
                rather than the real value.
                """
        )
        private final Map<String, String> outputs;
    }
}
