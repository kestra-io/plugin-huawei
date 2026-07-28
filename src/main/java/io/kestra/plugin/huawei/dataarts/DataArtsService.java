package io.kestra.plugin.huawei.dataarts;

import com.fasterxml.jackson.databind.JsonNode;
import com.huaweicloud.sdk.core.auth.AKSKSigner;
import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.core.http.HttpMethod;
import com.huaweicloud.sdk.core.http.HttpRequest;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.huawei.AbstractConnection;
import io.kestra.plugin.huawei.dataarts.models.JobRun;
import jakarta.annotation.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static REST helpers for the DataArts Factory V2 API.
 *
 * <p>The Huawei v3 Java SDK does not generate typed methods for the DLF job lifecycle, so requests
 * are built manually and signed via the SDK core's {@link AKSKSigner}. The JDK {@link HttpClient}
 * handles transport.
 *
 * <p><b>API version</b>: all routes are {@code /v2/{project_id}/factory/...}. The {@code /v1/…}
 * job-lifecycle paths this class previously used are not published on the API gateway — every
 * method/path combination returns HTTP 404 {@code APIGW.0101} ("The API does not exist or has not
 * been published in the environment"), verified against {@code dayu.tr-west-1.myhuaweicloud.com}.
 * The v2 factory paths match the {@code HttpRequestDef} metadata embedded in
 * {@code huaweicloud-sdk-dataartsstudio}'s {@code DataArtsStudioMeta}, which declares no {@code /v1/}
 * routes at all. When changing a path here, confirm it against that metadata rather than the
 * public API docs.
 *
 * <p><b>Transport exemption</b>: this class uses {@link java.net.http.HttpClient} (JDK) instead of
 * the Kestra internal HTTP client. The Huawei SDK's {@link AKSKSigner} requires a
 * {@link com.huaweicloud.sdk.core.http.HttpRequest} to compute the HMAC-SHA256 canonical request;
 * the Kestra client does not expose a compatible request type, so the raw JDK client is the only
 * viable transport here.
 */
public final class DataArtsService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // JDK's HttpRequest.Builder rejects these header names — filter them from signed headers.
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
        "connection", "content-length", "expect", "host", "transfer-encoding", "upgrade", "via"
    );

    // Carries STS temporary-credential tokens alongside AK/SK; must be signed *and* sent.
    private static final String SECURITY_TOKEN_HEADER = "X-Security-Token";

    private DataArtsService() {
    }

    /**
     * Terminal states: the job run will not advance further once in one of these states.
     * Returns {@code false} for {@code null} status (freshly-queued instances whose status
     * field has not yet been populated by the API).
     *
     * <p>Per the DataArts Factory instance status enum, finished states are {@code success},
     * {@code forceSuccess}, {@code ignoreSuccess}, {@code skip-by-depend}, {@code fail},
     * {@code running-exception}, and {@code manual-stop}. {@code waiting}, {@code running},
     * {@code waiting-confirm}, {@code freeze}, and {@code pause} are transient and not terminal.
     */
    public static boolean isTerminalState(String status) {
        if (status == null) return false;
        return switch (status) {
            case "success", "forceSuccess", "ignoreSuccess", "skip-by-depend",
                 "fail", "running-exception", "manual-stop" -> true;
            default -> false;
        };
    }

    /**
     * Successful terminal state — job completed without error. Includes {@code forceSuccess} and
     * {@code ignoreSuccess}, where an operator forced the instance to success or chose to ignore
     * the failure of a non-critical node.
     */
    public static boolean isSuccessState(String status) {
        return switch (status) {
            case "success", "forceSuccess", "ignoreSuccess" -> true;
            default -> false;
        };
    }

    /**
     * Triggers an immediate, on-demand run of {@code jobName} via the {@code run-immediate} route.
     *
     * <p><b>Why {@code run-immediate}, not {@code start}</b>: DataArts Factory exposes two distinct
     * job-trigger operations. {@code POST .../start} ("Starting a Job") turns on the job's
     * <em>schedule</em> and returns {@code DLF.3051} on a run-once job because there is no schedule
     * to start. {@code POST .../run-immediate} ("Executing a Job Immediately") performs a single
     * on-demand execution that produces exactly one instance — which is what this task's
     * resolve-the-new-instance / {@code wait}-and-poll contract needs. Per the DataArts Factory API,
     * the request takes an optional {@code {"jobParams":[{name,value}], "useExecutionUser":bool}}
     * body plus the {@code workspace} header (added by {@link #invoke}).
     *
     * <p>The response carries no instance ID this plugin relies on, so callers resolve the new
     * instance via {@link #listInstancesFirstPage} immediately after.
     *
     * <p><b>⚠ Region limitation ({@code tr-west-1} / T-Systems EU-sovereign)</b>: on that gateway
     * {@code run-immediate} rejects every request body with {@code DLF.3051 "The request parameter
     * is invalid."} — verified live against {@code dayu.tr-west-1.myhuaweicloud.com} for both a
     * run-once and a periodically-scheduled job, with empty / {@code jobParams} / {@code
     * useExecutionUser} bodies. The route is absent from the documented V2 API family; the deprecated
     * {@code /v1/{pid}/jobs/{name}/run-immediate} path 404s with {@code APIGW.0101} there, and the
     * console's own {@code /v1.0/{ws}/pipelines/run-pipelines} call is session-authenticated and not
     * exposed on the public AK/SK gateway. GET operations on the same {@code /v2/.../factory/jobs}
     * family (list/get instances) work, so this is a per-region gateway limitation, not a
     * signing/transport bug. Standard (non-sovereign) regions are expected to accept the call.
     *
     * @param endpoint    base DataArts endpoint (no trailing slash)
     * @param projectId   Huawei Cloud project ID
     * @param workspaceId workspace header value (null or blank → header omitted)
     * @param jobName     name of the job to run
     * @param jobParams   optional job-level parameters (key=value map)
     */
    public static void startJob(
        RunContext runContext,
        AbstractConnection.HuaweiClientConfig config,
        String endpoint,
        String projectId,
        @Nullable String workspaceId,
        String jobName,
        @Nullable Map<String, String> jobParams
    ) throws Exception {
        var path = "/v2/" + projectId + "/factory/jobs/" + urlEncode(jobName) + "/run-immediate";

        var bodyMap = new LinkedHashMap<String, Object>();
        if (jobParams != null && !jobParams.isEmpty()) {
            bodyMap.put("jobParams", jobParams.entrySet().stream()
                .map(e -> Map.of("name", e.getKey(), "value", e.getValue()))
                .toList());
        }
        var body = bodyMap.isEmpty() ? "{}" : JacksonMapper.ofJson().writeValueAsString(bodyMap);

        var response = invoke(config, endpoint, path, "POST", workspaceId, body);

        if (response.statusCode() != 204 && response.statusCode() != 200) {
            var detail = parseDlfError(response.body());
            var hint = detail.contains("DLF.3051")
                ? " — 'run-immediate' is not usable on some sovereign gateways (e.g. tr-west-1), which"
                    + " reject every body with DLF.3051; on those regions StartJobRun is unsupported."
                : " — check that the job name is correct and the credentials have permission to run the job.";
            throw new IllegalStateException(
                "DataArts Factory run job '" + jobName + "' failed (HTTP " + response.statusCode() + ")" +
                detail + hint);
        }
        runContext.logger().debug("Run-immediate for job '{}' accepted (HTTP {})", jobName, response.statusCode());
    }

    /**
     * Fetches only the first page of job run instances (single API call), newest first.
     * Use this when only the most-recent instance is needed to avoid O(n/limit) requests.
     */
    public static List<JobRun> listInstancesFirstPage(
        AbstractConnection.HuaweiClientConfig config,
        String endpoint,
        String projectId,
        @Nullable String workspaceId,
        String jobName,
        int limit
    ) throws Exception {
        return fetchInstancePage(config, endpoint, projectId, workspaceId, jobName, limit, 0);
    }

    /**
     * Lists all job run instances for {@code jobName}, newest first (by planTime/startTime desc).
     * Paginates automatically until all instances have been fetched or a page is shorter than limit.
     *
     * @param limit page size (1–100)
     */
    public static List<JobRun> listInstances(
        AbstractConnection.HuaweiClientConfig config,
        String endpoint,
        String projectId,
        @Nullable String workspaceId,
        String jobName,
        int limit
    ) throws Exception {
        var result = new ArrayList<JobRun>();
        int offset = 0;

        while (true) {
            var page = fetchInstancePage(config, endpoint, projectId, workspaceId, jobName, limit, offset);
            result.addAll(page);
            if (page.size() < limit) {
                break;
            }
            offset += limit;
        }

        // Sort newest first across all pages: the API does not guarantee stable ordering when
        // results span multiple pages, so a client-side sort is required for correctness.
        result.sort((a, b) -> {
            var ta = a.getPlanTime() != null ? a.getPlanTime() : (a.getStartTime() != null ? a.getStartTime() : 0L);
            var tb = b.getPlanTime() != null ? b.getPlanTime() : (b.getStartTime() != null ? b.getStartTime() : 0L);
            return Long.compare(tb, ta);
        });
        return result;
    }

    private static List<JobRun> fetchInstancePage(
        AbstractConnection.HuaweiClientConfig config,
        String endpoint,
        String projectId,
        @Nullable String workspaceId,
        String jobName,
        int limit,
        int offset
    ) throws Exception {
        // v2 carries the job name as a path segment, so the exact-match behaviour the v1 route
        // needed `jobName` + `preciseQuery=true` query params for is now inherent — a path segment
        // cannot substring-match a differently-named job.
        var path = "/v2/" + projectId + "/factory/jobs/" + urlEncode(jobName) + "/instances/detail"
            + "?limit=" + limit
            + "&offset=" + offset;

        var response = invoke(config, endpoint, path, "GET", workspaceId, null);

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "DataArts Factory list instances for job '" + jobName + "' failed (HTTP " +
                response.statusCode() + ")" + parseDlfError(response.body()) +
                " — verify the jobName and that the credentials have dlf:jobs:query permission.");
        }

        var root = JacksonMapper.ofJson().readTree(response.body());
        var instances = root.path("instances");
        if (instances.isMissingNode() || !instances.isArray() || instances.isEmpty()) {
            return List.of();
        }

        var page = new ArrayList<JobRun>(instances.size());
        for (var node : instances) {
            page.add(nodeToJobRun(jobName, node));
        }
        return page;
    }

    /**
     * Fetches the detail of a specific job run instance.
     */
    public static JobRun getInstance(
        AbstractConnection.HuaweiClientConfig config,
        String endpoint,
        String projectId,
        @Nullable String workspaceId,
        String jobName,
        long instanceId
    ) throws Exception {
        var path = "/v2/" + projectId + "/factory/jobs/" + urlEncode(jobName) + "/instances/" + instanceId;
        var response = invoke(config, endpoint, path, "GET", workspaceId, null);

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "DataArts Factory get instance " + instanceId + " for job '" + jobName +
                "' failed (HTTP " + response.statusCode() + ")" + parseDlfError(response.body()) +
                " — verify the instanceId and job name.");
        }

        var node = JacksonMapper.ofJson().readTree(response.body());
        return nodeToJobRun(jobName, node);
    }

    /**
     * Sends a stop request for a specific job run instance.
     *
     * <p>Returns HTTP 204 on success.
     *
     * <p><b>⚠ Unverified route — this method is expected to fail.</b> Unlike {@code startJob},
     * {@code listInstances} and {@code getInstance}, no working stop route has been found. The
     * {@code /v1/} path below returns {@code APIGW.0101} (not published) like every other v1 path,
     * and none of the plausible v2 shapes exist either — all of
     * {@code POST|PUT /v2/{pid}/factory/jobs/{job}/instances/{id}/stop},
     * {@code POST /v2/{pid}/factory/jobs/{job}/stop},
     * {@code POST /v2/{pid}/factory/jobs/{job}/instances/stop} and
     * {@code POST /v2/{pid}/factory/jobs/instances/{id}/stop} return {@code APIGW.0101} against
     * {@code dayu.tr-west-1.myhuaweicloud.com}. {@code DataArtsStudioMeta} declares no stop route
     * for factory jobs either (only {@code instances/detail}, {@code instances/retry},
     * {@code rename}, {@code tags} — plus an unrelated {@code supplement-data/{name}/stop}).
     *
     * <p>Resolving this needs Huawei's DLF API reference or an authenticated probe from an account
     * with a running instance. The path is deliberately left on {@code /v1/} rather than guessed
     * into a v2 shape, so the failure stays honest instead of looking migrated.
     */
    // TODO(dataarts-stop-route): find the published stop-instance route and migrate to /v2/factory.
    public static void stopInstance(
        RunContext runContext,
        AbstractConnection.HuaweiClientConfig config,
        String endpoint,
        String projectId,
        @Nullable String workspaceId,
        String jobName,
        long instanceId
    ) throws Exception {
        var path = "/v1/" + projectId + "/jobs/" + urlEncode(jobName) + "/instances/" + instanceId + "/stop";
        var response = invoke(config, endpoint, path, "POST", workspaceId, "{}");

        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw new IllegalStateException(
                "DataArts Factory stop instance " + instanceId + " for job '" + jobName +
                "' failed (HTTP " + response.statusCode() + ")" + parseDlfError(response.body()) +
                " — check that the instance is in a stoppable state.");
        }
        runContext.logger().debug("Stop instance {} for job '{}' accepted (HTTP {})", instanceId, jobName, response.statusCode());
    }

    // ── Internal helpers ─────────────────────────────────────────────────────────

    private static HttpResponse<String> invoke(
        AbstractConnection.HuaweiClientConfig config,
        String endpoint,
        String path,
        String method,
        @Nullable String workspaceId,
        @Nullable String body
    ) throws IOException, InterruptedException {
        var url = endpoint + path;

        // Build the SDK HttpRequest for signing — path must not include the host.
        // Extract path+query from the full URL for the signer (it derives the host from endpoint).
        var pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        var query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";

        var sdkReqBuilder = HttpRequest.newBuilder()
            .withEndpoint(endpoint)
            .withPath(pathOnly)
            .withMethod(HttpMethod.valueOf(method))
            .withContentType("application/json");

        if (!query.isBlank()) {
            // addQueryParam expects List<String>; the query is already encoded so we re-parse it
            for (var kv : query.split("&")) {
                var eq = kv.indexOf('=');
                if (eq > 0) {
                    var k = kv.substring(0, eq);
                    var v = kv.substring(eq + 1);
                    sdkReqBuilder.addQueryParam(k, List.of(v));
                }
            }
        }

        // Always sign the exact body bytes that will be sent on the wire so HMAC-SHA256 matches.
        if (body != null) {
            sdkReqBuilder.withBodyAsString(body);
        }

        // Temporary (STS) credentials only resolve when X-Security-Token accompanies the AK/SK —
        // without it IAM's permanent-key lookup misses and the gateway answers
        // `APIGW.0301 / ak <ak> not exist`. AKSKSigner#sign ignores BasicCredentials#withSecurityToken
        // entirely: the SDK injects this header in BasicCredentials#syncProcessAuthRequest, which the
        // manual signing path here never calls. Add it *before* signing (verified: SignedHeaders then
        // becomes `host;x-sdk-date;x-security-token`) and again on the JDK request below, because the
        // map returned by sign() holds only Authorization/Host/X-Sdk-Date and would otherwise leave
        // the signature covering a header that never reaches the wire.
        var securityToken = config.securityToken();
        var hasSecurityToken = securityToken != null && !securityToken.isBlank();
        if (hasSecurityToken) {
            sdkReqBuilder.addHeader(SECURITY_TOKEN_HEADER, securityToken);
        }

        var sdkReq = sdkReqBuilder.build();

        // Sign with AK/SK if available; otherwise fall back to X-Auth-Token.
        // Content-Type is NOT set here — AKSKSigner includes it in the signed headers map
        // (from withContentType above), so the loop below supplies exactly one value that
        // matches what was signed. Setting it here too would produce a doubled header on
        // the wire and break the HMAC verification on the server.
        var jdkReqBuilder = java.net.http.HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30));

        if (workspaceId != null && !workspaceId.isBlank()) {
            jdkReqBuilder.header("workspace", workspaceId);
        }

        if (config.accessKeyId() != null && !config.accessKeyId().isBlank()
                && config.secretAccessKey() != null && !config.secretAccessKey().isBlank()) {
            var signingCreds = new BasicCredentials()
                .withAk(config.accessKeyId())
                .withSk(config.secretAccessKey());
            var signedHeaders = AKSKSigner.getInstance().sign(sdkReq, signingCreds);
            // JDK HttpRequest.Builder rejects restricted headers (Host, Connection, …) that the
            // signer adds for canonical request computation. The JDK sets Host automatically.
            signedHeaders.entrySet().stream()
                .filter(e -> !RESTRICTED_HEADERS.contains(e.getKey().toLowerCase()))
                .forEach(e -> jdkReqBuilder.header(e.getKey(), e.getValue()));
            if (hasSecurityToken) {
                jdkReqBuilder.header(SECURITY_TOKEN_HEADER, securityToken);
            }
        } else if (hasSecurityToken) {
            jdkReqBuilder.header("X-Auth-Token", securityToken);
        } else {
            throw new IllegalArgumentException(
                "DataArts Studio requires either AK/SK credentials (accessKeyId + secretAccessKey) " +
                "or a security token — configure at least one authentication method.");
        }

        // POST with no body still needs a publisher; noBody() works for GET.
        var bodyPublisher = body != null
            ? BodyPublishers.ofString(body)
            : ("GET".equals(method) ? BodyPublishers.noBody() : BodyPublishers.ofString(""));

        var jdkReq = jdkReqBuilder
            .method(method, bodyPublisher)
            .build();

        return HTTP_CLIENT.send(jdkReq, HttpResponse.BodyHandlers.ofString());
    }

    private static JobRun nodeToJobRun(String jobName, JsonNode node) {
        return JobRun.builder()
            .jobName(jobName)
            .instanceId(longOrNull(node, "instanceId"))
            .status(textOrNull(node, "status"))
            .planTime(longOrNull(node, "planTime"))
            .startTime(longOrNull(node, "startTime"))
            .endTime(longOrNull(node, "endTime"))
            .lastUpdateTime(longOrNull(node, "lastUpdateTime"))
            .errorMessage(textOrNull(node, "errorMessage"))
            .build();
    }

    private static String textOrNull(JsonNode node, String field) {
        var n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static Long longOrNull(JsonNode node, String field) {
        var n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asLong();
    }

    /**
     * Parses DataArts DLF error JSON and returns a formatted detail string.
     * Only the safe structured fields are included — raw body is never exposed.
     */
    private static String parseDlfError(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            var root = JacksonMapper.ofJson().readTree(body);
            var code = textOrNull(root, "error_code");
            var msg = textOrNull(root, "error_msg");
            if (code != null || msg != null) {
                var sb = new StringBuilder(": ");
                if (msg != null) sb.append(msg);
                if (code != null) sb.append(msg != null ? " [" : "[").append("code=").append(code).append(']');
                return sb.length() > 2 ? sb.toString() : "";
            }
        } catch (Exception ignored) {
            // unparseable body — omit from message
        }
        return "";
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
