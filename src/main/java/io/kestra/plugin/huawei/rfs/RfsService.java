package io.kestra.plugin.huawei.rfs;

import com.huaweicloud.sdk.aos.v1.AosClient;
import com.huaweicloud.sdk.aos.v1.model.CreateStackRequest;
import com.huaweicloud.sdk.aos.v1.model.CreateStackRequestBody;
import com.huaweicloud.sdk.aos.v1.model.CreateStackResponse;
import com.huaweicloud.sdk.aos.v1.model.DeleteStackRequest;
import com.huaweicloud.sdk.aos.v1.model.DeployStackRequest;
import com.huaweicloud.sdk.aos.v1.model.DeployStackRequestBody;
import com.huaweicloud.sdk.aos.v1.model.DeployStackResponse;
import com.huaweicloud.sdk.aos.v1.model.GetStackMetadataRequest;
import com.huaweicloud.sdk.aos.v1.model.GetStackMetadataResponse;
import com.huaweicloud.sdk.aos.v1.model.ListStackOutputsRequest;
import com.huaweicloud.sdk.aos.v1.model.VarsStructure;
import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reusable RFS stack operations shared by {@link Create} and {@link Delete}: existence probing,
 * create/deploy/delete calls, output listing, and terminal-state polling. Kept static and free of
 * RunContext, mirroring {@code DliService}.
 */
final class RfsService {

    private RfsService() {
    }

    /** Returns the current stack metadata, or {@code null} if the stack does not exist (HTTP 404). */
    static GetStackMetadataResponse probe(AosClient client, String stackName) {
        try {
            return client.getStackMetadata(new GetStackMetadataRequest()
                .withStackName(stackName)
                .withClientRequestId(UUID.randomUUID().toString()));
        } catch (ServiceResponseException e) {
            if (e.getHttpStatusCode() == 404) {
                return null;
            }
            throw new IllegalStateException(
                "Failed to check RFS stack '" + stackName + "' (HTTP " + e.getHttpStatusCode() + "): " + e.getErrorMsg() +
                " — verify the stack name and that the AK/SK has RFS permissions.", e);
        } catch (SdkException e) {
            throw new IllegalStateException("RFS SDK error checking stack '" + stackName + "': " + e.getMessage(), e);
        }
    }

    static CreateStackResponse create(
        AosClient client, String stackName, String templateBody, String templateUri,
        List<VarsStructure> varsStructure, String varsBody, String varsUri,
        String description, Boolean enableDeletionProtection, Boolean enableAutoRollback
    ) {
        var body = new CreateStackRequestBody().withStackName(stackName);
        if (templateBody != null) {
            body.withTemplateBody(templateBody);
        }
        if (templateUri != null) {
            body.withTemplateUri(templateUri);
        }
        if (varsStructure != null && !varsStructure.isEmpty()) {
            body.withVarsStructure(varsStructure);
        }
        if (varsBody != null) {
            body.withVarsBody(varsBody);
        }
        if (varsUri != null) {
            body.withVarsUri(varsUri);
        }
        if (description != null) {
            body.withDescription(description);
        }
        if (enableDeletionProtection != null) {
            body.withEnableDeletionProtection(enableDeletionProtection);
        }
        if (enableAutoRollback != null) {
            body.withEnableAutoRollback(enableAutoRollback);
        }

        try {
            return client.createStack(new CreateStackRequest()
                .withClientRequestId(UUID.randomUUID().toString())
                .withBody(body));
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "Failed to create RFS stack '" + stackName + "' (HTTP " + e.getHttpStatusCode() + "): " + e.getErrorMsg() +
                " — verify the template syntax and that the AK/SK has RFS permissions.", e);
        } catch (SdkException e) {
            throw new IllegalStateException("RFS SDK error creating stack '" + stackName + "': " + e.getMessage(), e);
        }
    }

    static DeployStackResponse deploy(
        AosClient client, String stackName, String templateBody, String templateUri,
        List<VarsStructure> varsStructure, String varsBody, String varsUri
    ) {
        var body = new DeployStackRequestBody();
        if (templateBody != null) {
            body.withTemplateBody(templateBody);
        }
        if (templateUri != null) {
            body.withTemplateUri(templateUri);
        }
        if (varsStructure != null && !varsStructure.isEmpty()) {
            body.withVarsStructure(varsStructure);
        }
        if (varsBody != null) {
            body.withVarsBody(varsBody);
        }
        if (varsUri != null) {
            body.withVarsUri(varsUri);
        }

        try {
            return client.deployStack(new DeployStackRequest()
                .withStackName(stackName)
                .withClientRequestId(UUID.randomUUID().toString())
                .withBody(body));
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "Failed to deploy RFS stack '" + stackName + "' (HTTP " + e.getHttpStatusCode() + "): " + e.getErrorMsg() +
                " — verify the template syntax and that the AK/SK has RFS permissions.", e);
        } catch (SdkException e) {
            throw new IllegalStateException("RFS SDK error deploying stack '" + stackName + "': " + e.getMessage(), e);
        }
    }

    static void delete(AosClient client, String stackName) {
        try {
            client.deleteStack(new DeleteStackRequest()
                .withStackName(stackName)
                .withClientRequestId(UUID.randomUUID().toString()));
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "Failed to delete RFS stack '" + stackName + "' (HTTP " + e.getHttpStatusCode() + "): " + e.getErrorMsg() +
                " — the stack may have deletion protection enabled, or its resources may still be in use.", e);
        } catch (SdkException e) {
            throw new IllegalStateException("RFS SDK error deleting stack '" + stackName + "': " + e.getMessage(), e);
        }
    }

    /** Page size and safety cap for {@link #listOutputs}: 100 pages × 100 = 10,000 outputs bounds a
     *  pathological or misbehaving-marker response without ever truncating a realistic stack. */
    private static final int OUTPUT_PAGE_SIZE = 100;
    static final int MAX_OUTPUT_PAGES = 100;

    /** Paginates through every declared output via {@code marker}, keyed by output name. Bounded at
     *  {@link #MAX_OUTPUT_PAGES} pages; if that cap is hit the truncation is logged rather than
     *  silently swallowed. */
    static Map<String, String> listOutputs(AosClient client, String stackName, Logger logger) {
        var outputs = new LinkedHashMap<String, String>();
        String marker = null;
        int page = 0;

        while (true) {
            var request = new ListStackOutputsRequest()
                .withStackName(stackName)
                .withClientRequestId(UUID.randomUUID().toString())
                .withLimit(OUTPUT_PAGE_SIZE);
            if (marker != null) {
                request.withMarker(marker);
            }
            try {
                var response = client.listStackOutputs(request);
                if (response.getOutputs() != null) {
                    response.getOutputs().forEach(o -> outputs.put(o.getName(), o.getValue()));
                }
                marker = response.getPageInfo() != null ? response.getPageInfo().getNextMarker() : null;
            } catch (ServiceResponseException e) {
                throw new IllegalStateException(
                    "Failed to list outputs for RFS stack '" + stackName + "' (HTTP " + e.getHttpStatusCode() + "): " +
                    e.getErrorMsg(), e);
            } catch (SdkException e) {
                throw new IllegalStateException(
                    "RFS SDK error listing outputs for stack '" + stackName + "': " + e.getMessage(), e);
            }
            if (marker == null || marker.isBlank()) {
                break;
            }
            if (++page >= MAX_OUTPUT_PAGES) {
                logger.warn(
                    "RFS stack '{}' returned more than {} pages of outputs ({} collected so far); " +
                    "stopping pagination — the 'outputs' map is truncated.",
                    stackName, MAX_OUTPUT_PAGES, outputs.size());
                break;
            }
        }
        return outputs;
    }

    static boolean isInProgress(GetStackMetadataResponse.StatusEnum status) {
        return status == GetStackMetadataResponse.StatusEnum.DEPLOYMENT_IN_PROGRESS
            || status == GetStackMetadataResponse.StatusEnum.ROLLBACK_IN_PROGRESS
            || status == GetStackMetadataResponse.StatusEnum.DELETION_IN_PROGRESS;
    }

    /**
     * Polls {@code getStackMetadata} until the deployment leaves an in-progress state, bounded by
     * {@code maxDuration}. The caller decides whether the returned terminal status counts as success
     * — only {@code DEPLOYMENT_COMPLETE} does; {@code CREATION_COMPLETE} (template never deployed),
     * {@code ROLLBACK_COMPLETE}, and the {@code *_FAILED} states are all treated as failure outcomes.
     */
    static GetStackMetadataResponse pollUntilDeployTerminal(
        AosClient client, String stackName, Duration interval, Duration maxDuration, Logger logger
    ) throws InterruptedException {
        var deadline = System.currentTimeMillis() + maxDuration.toMillis();
        var current = probeOrThrow(client, stackName);

        while (isInProgress(current.getStatus())) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "RFS stack '" + stackName + "' did not reach a terminal state within " + maxDuration +
                    " — current status: " + current.getStatus() +
                    ". Increase 'maxDuration', or check the RFS console for a stuck deployment.");
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            current = probeOrThrow(client, stackName);
            logger.debug("RFS stack '{}' status={}", stackName, current.getStatus());
        }

        return current;
    }

    /**
     * Polls {@code getStackMetadata} until the stack is gone (HTTP 404) or reaches
     * {@code DELETION_FAILED}, bounded by {@code maxDuration}. RFS has no {@code DELETION_COMPLETE}
     * status: a successfully deleted stack simply stops existing.
     */
    static void pollUntilDeleted(
        AosClient client, String stackName, Duration interval, Duration maxDuration, Logger logger
    ) throws InterruptedException {
        var deadline = System.currentTimeMillis() + maxDuration.toMillis();
        var current = probe(client, stackName);

        while (current != null) {
            if (current.getStatus() == GetStackMetadataResponse.StatusEnum.DELETION_FAILED) {
                throw new IllegalStateException(
                    "RFS stack '" + stackName + "' deletion failed" +
                    (current.getStatusMessage() != null ? ": " + current.getStatusMessage() : "") +
                    " — the stack may have deletion protection enabled, or its resources may still be in use.");
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "RFS stack '" + stackName + "' was not deleted within " + maxDuration +
                    " — current status: " + current.getStatus() +
                    ". Increase 'maxDuration', or check the RFS console for a stuck deletion.");
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            current = probe(client, stackName);
            if (current != null) {
                logger.debug("RFS stack '{}' status={}", stackName, current.getStatus());
            }
        }
    }

    private static GetStackMetadataResponse probeOrThrow(AosClient client, String stackName) {
        var result = probe(client, stackName);
        if (result == null) {
            throw new IllegalStateException(
                "RFS stack '" + stackName + "' is not visible via getStackMetadata while polling for " +
                "deployment completion — it may not have propagated yet immediately after creation, or it " +
                "may have been deleted concurrently by another process. Retry, or check the RFS console.");
        }
        return result;
    }
}
