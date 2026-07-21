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
import com.huaweicloud.sdk.aos.v1.model.ListStackEventsRequest;
import com.huaweicloud.sdk.aos.v1.model.ListStackOutputsRequest;
import com.huaweicloud.sdk.aos.v1.model.StackEvent;
import com.huaweicloud.sdk.aos.v1.model.VarsStructure;
import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private static final int MAX_OUTPUT_PAGES = 100;

    /** Bounded retries for the first poll right after {@code createStack}: a 404 there is almost
     *  always an eventual-consistency propagation delay rather than a genuinely missing stack, so
     *  give it a few grace attempts (at {@code interval}) before failing. */
    private static final int POST_CREATE_PROPAGATION_RETRIES = 3;

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

    /** How many stack events to fetch/scan when building a failure explanation. */
    private static final int FAILURE_EVENTS_LIMIT = 50;
    /** Cap on how many event lines to include in the thrown message, newest-relevant first. */
    private static final int MAX_REPORTED_EVENTS = 10;
    private static final int MAX_EVENT_MESSAGE_CHARS = 500;
    /** Substrings that mark a LOG-type event as carrying a failure cause (RFS often reports the real
     *  Terraform error — e.g. "Failed to init workflow due to bad template" — as a LOG, not an ERROR). */
    private static final List<String> FAILURE_KEYWORDS =
        List.of("fail", "error", "not found", "denied", "unable", "cannot", "invalid", "conflict", "exist");

    /**
     * Best-effort human-readable explanation of why a deployment failed, built from {@code listStackEvents}.
     * Returns the relevant ERROR/{@code *_FAILED} events plus any LOG events whose message mentions a
     * failure keyword, so the caller can surface the real cause without a console round-trip. Returns
     * {@code null} if nothing useful could be retrieved. Never throws — surfacing the cause must never
     * mask the original failure (e.g. if the AK/SK lacks {@code listStackEvents} permission).
     */
    static String describeFailure(AosClient client, String stackName, String deploymentId, Logger logger) {
        try {
            var request = new ListStackEventsRequest()
                .withStackName(stackName)
                .withClientRequestId(UUID.randomUUID().toString())
                .withLimit(FAILURE_EVENTS_LIMIT);
            if (deploymentId != null) {
                request.withDeploymentId(deploymentId);
            }
            var events = client.listStackEvents(request).getStackEvents();
            if (events == null || events.isEmpty()) {
                return null;
            }
            var relevant = events.stream()
                .filter(RfsService::isRelevantFailureEvent)
                .map(RfsService::formatEvent)
                .filter(m -> m != null && !m.isBlank())
                .distinct()
                .limit(MAX_REPORTED_EVENTS)
                .collect(Collectors.joining("\n  - "));
            return relevant.isBlank() ? null : "  - " + relevant;
        } catch (SdkException e) {
            logger.debug("Could not fetch RFS deployment events for stack '{}' to explain the failure: {}",
                stackName, e.getMessage());
            return null;
        }
    }

    private static boolean isRelevantFailureEvent(StackEvent event) {
        var type = event.getEventType();
        if (type == StackEvent.EventTypeEnum.ERROR
            || type == StackEvent.EventTypeEnum.CREATION_FAILED
            || type == StackEvent.EventTypeEnum.DELETION_FAILED
            || type == StackEvent.EventTypeEnum.UPDATE_FAILED) {
            return true;
        }
        // RFS frequently reports the underlying Terraform error as a LOG event, so scan its message.
        var message = event.getEventMessage();
        if (message == null) {
            return false;
        }
        var lower = message.toLowerCase(Locale.ROOT);
        return FAILURE_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private static String formatEvent(StackEvent event) {
        var message = event.getEventMessage();
        if (message == null || message.isBlank()) {
            return null;
        }
        var trimmed = message.strip();
        if (trimmed.length() > MAX_EVENT_MESSAGE_CHARS) {
            trimmed = trimmed.substring(0, MAX_EVENT_MESSAGE_CHARS) + "…";
        }
        var resource = event.getResourceName() != null && !event.getResourceName().isBlank()
            ? " (" + event.getResourceName() + ")" : "";
        return "[" + event.getEventType() + "]" + resource + " " + trimmed;
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
        // First poll right after createStack: tolerate a brief propagation 404 with bounded retries
        // before treating the stack as genuinely missing.
        var current = probeAfterSubmit(client, stackName, interval, logger);

        while (isInProgress(current.getStatus())) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "RFS stack '" + stackName + "' did not reach a terminal state within " + maxDuration +
                    " — current status: " + current.getStatus() +
                    ". Increase 'maxDuration', or check the RFS console for a stuck deployment.");
            }
            sleepOrThrow(interval);
            // A 404 here means the stack vanished after being visible — genuinely exceptional.
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
                var detail = describeFailure(client, stackName, null, logger);
                throw new IllegalStateException(
                    "RFS stack '" + stackName + "' deletion failed" +
                    (current.getStatusMessage() != null ? ": " + current.getStatusMessage() : "") +
                    " — the stack may have deletion protection enabled, or its resources may still be in use." +
                    (detail != null ? "\nFailure details from RFS:\n" + detail : ""));
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "RFS stack '" + stackName + "' was not deleted within " + maxDuration +
                    " — current status: " + current.getStatus() +
                    ". Increase 'maxDuration', or check the RFS console for a stuck deletion.");
            }
            sleepOrThrow(interval);
            current = probe(client, stackName);
            if (current != null) {
                logger.debug("RFS stack '{}' status={}", stackName, current.getStatus());
            }
        }
    }

    /**
     * First poll after a create/deploy submission: retries a propagation 404 up to
     * {@link #POST_CREATE_PROPAGATION_RETRIES} times (at {@code interval}) before failing, since a
     * stack that was just accepted for creation may not yet be visible via {@code getStackMetadata}.
     */
    private static GetStackMetadataResponse probeAfterSubmit(
        AosClient client, String stackName, Duration interval, Logger logger
    ) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            var result = probe(client, stackName);
            if (result != null) {
                return result;
            }
            if (attempt >= POST_CREATE_PROPAGATION_RETRIES) {
                throw new IllegalStateException(
                    "RFS stack '" + stackName + "' is still not visible via getStackMetadata after " +
                    POST_CREATE_PROPAGATION_RETRIES + " attempts following its submission — it may not have " +
                    "propagated yet, or the create/deploy request may have been rejected. Retry, or check the RFS console.");
            }
            logger.debug(
                "RFS stack '{}' not visible yet (attempt {}/{}) — waiting {} for creation to propagate.",
                stackName, attempt, POST_CREATE_PROPAGATION_RETRIES, interval);
            sleepOrThrow(interval);
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

    /** Sleeps for {@code interval}, restoring the interrupt flag and rethrowing on interruption. */
    private static void sleepOrThrow(Duration interval) throws InterruptedException {
        try {
            Thread.sleep(interval.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        }
    }
}
