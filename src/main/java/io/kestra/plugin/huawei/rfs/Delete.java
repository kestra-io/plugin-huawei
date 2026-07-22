package io.kestra.plugin.huawei.rfs;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Delete a Huawei Cloud RFS stack",
    description = """
        Deletes an RFS stack and, when `wait` is `true` (the default), polls `getStackMetadata` until
        the stack is gone. RFS has no `DELETION_COMPLETE` status: a successfully deleted stack simply
        stops existing, surfaced as an HTTP 404 on the next metadata check.

        By default (`errorOnMissing: false`), deleting a stack that does not exist is a no-op logged
        at `INFO` level, making this task safe to run idempotently.

        This task intentionally does not override `kill()`: it is itself the explicit teardown, so
        killing the Kestra execution only stops the client-side polling — the deletion already
        submitted keeps running on Huawei's side.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Delete an RFS stack and wait for it to be fully removed",
            code = """
                id: rfs_delete_stack
                namespace: company.team

                tasks:
                  - id: delete_stack
                    type: io.kestra.plugin.huawei.rfs.Delete
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    stackName: my-bucket-stack
                """
        )
    }
)
public class Delete extends AbstractRfs implements RunnableTask<VoidOutput> {

    @Builder.Default
    @Schema(
        title = "Fail when the stack does not exist",
        description = "When `false` (the default), a missing stack is treated as success and logged at `INFO` level. Set to `true` to fail the task instead."
    )
    @PluginProperty(group = "reliability")
    private Property<Boolean> errorOnMissing = Property.ofValue(false);

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        var rStackName = runContext.render(stackName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("'stackName' is required"));
        var rErrorOnMissing = runContext.render(errorOnMissing).as(Boolean.class).orElse(false);
        var rWait = runContext.render(wait).as(Boolean.class).orElse(true);
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(Duration.ofHours(1));
        var rInterval = runContext.render(interval).as(Duration.class).orElse(Duration.ofSeconds(5));

        var client = client(runContext);
        var logger = runContext.logger();

        var existing = RfsService.probe(client, rStackName);
        if (existing == null) {
            if (rErrorOnMissing) {
                throw new IllegalStateException(
                    "RFS stack '" + rStackName + "' does not exist — nothing to delete. " +
                    "Set 'errorOnMissing' to false to treat this as a no-op.");
            }
            logger.info("RFS stack '{}' does not exist — nothing to delete.", rStackName);
            return null;
        }

        logger.info("Deleting RFS stack '{}'.", rStackName);
        RfsService.delete(client, rStackName);

        if (!rWait) {
            logger.info("RFS stack '{}' deletion submitted; not waiting for completion.", rStackName);
            return null;
        }

        RfsService.pollUntilDeleted(client, rStackName, rInterval, rMaxDuration, logger);
        logger.info("RFS stack '{}' deleted.", rStackName);

        return null;
    }
}
