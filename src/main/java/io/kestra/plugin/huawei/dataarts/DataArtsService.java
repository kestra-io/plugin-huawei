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
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static REST helpers for the DataArts Factory V1 API.
 *
 * <p>The Huawei v3 Java SDK does not generate typed methods for the DLF job lifecycle, so requests
 * are built manually and signed via the SDK core's {@link AKSKSigner}. The JDK {@link HttpClient}
 * handles transport so we reuse the same HTTP stack already present in {@link ConnectionUtils}.
 */
public final class DataArtsService {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // JDK's HttpRequest.Builder rejects these header names — filter them from signed headers.
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
        "connection", "content-length", "expect", "host", "upgrade"
    );

    private DataArtsService() {
    }

    /**
     * Terminal states: the job run will not advance further once in one of these states.
     */
    public static boolean isTerminalState(String status) {
        return switch (status) {
            case "success", "fail", "running-exception", "manual-stop" -> true;
            default -> false;
        };
    }

    /**
     * Successful terminal state — job completed without error.
     */
    public static boolean isSuccessState(String status) {
        return "success".equals(status);
    }

    /**
     * Posts a start request for {@code jobName}.
     *
     * <p>DataArts Factory returns HTTP 204 with no body on success; no instance ID is produced.
     * Call {@link #listInstances} immediately after to resolve the resulting instance.
     *
     * @param endpoint    base DataArts endpoint (no trailing slash)
     * @param projectId   Huawei Cloud project ID
     * @param workspaceId workspace header value (null or blank → header omitted)
     * @param jobName     name of the job to start
     * @param jobParams   optional job-level parameters (key=value map)
     * @param startDate   optional start date string accepted by the API
     */
    public static void startJob(
        RunContext runContext,
        AbstractConnection.HuaweiClientConfig config,
        String endpoint,
        String projectId,
        @Nullable String workspaceId,
        String jobName,
        @Nullable Map<String, String> jobParams,
        @Nullable String startDate
    ) throws Exception {
        var path = "/v1/" + projectId + "/jobs/" + urlEncode(jobName) + "/start";
        var bodyMap = new java.util.LinkedHashMap<String, Object>();
        if (jobParams != null && !jobParams.isEmpty()) {
            bodyMap.put("jobParams", jobParams.entrySet().stream()
                .map(e -> Map.of("name", e.getKey(), "value", e.getValue()))
                .toList());
        }
        if (startDate != null && !startDate.isBlank()) {
            bodyMap.put("startDate", startDate);
        }
        var body = bodyMap.isEmpty() ? "{}" : JacksonMapper.ofJson().writeValueAsString(bodyMap);

        var response = invoke(config, endpoint, path, "POST", workspaceId, body);

        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw new IllegalStateException(
                "DataArts Factory start job '" + jobName + "' failed (HTTP " + response.statusCode() + ")" +
                parseDlfError(response.body()) +
                " — check that the job name is correct and the credentials have the dlf:jobs:start permission.");
        }
        runContext.logger().debug("Start job '{}' request accepted (HTTP {})", jobName, response.statusCode());
    }

    /**
     * Lists job run instances for {@code jobName}, newest first (by planTime/startTime desc).
     *
     * <p>Paginates automatically using limit/offset until all instances have been fetched or the
     * list is empty.
     *
     * @param limit      page size (1–100)
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
            var path = "/v1/" + projectId + "/jobs/instances/detail"
                + "?jobName=" + urlEncode(jobName)
                + "&limit=" + limit
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
                break;
            }

            for (var node : instances) {
                result.add(nodeToJobRun(jobName, node));
            }

            if (instances.size() < limit) {
                break;
            }
            offset += limit;
        }

        // Sort newest first: prefer planTime, fall back to startTime, then 0.
        result.sort((a, b) -> {
            var ta = a.getPlanTime() != null ? a.getPlanTime() : (a.getStartTime() != null ? a.getStartTime() : 0L);
            var tb = b.getPlanTime() != null ? b.getPlanTime() : (b.getStartTime() != null ? b.getStartTime() : 0L);
            return Long.compare(tb, ta);
        });
        return result;
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
        var path = "/v1/" + projectId + "/jobs/" + urlEncode(jobName) + "/instances/" + instanceId;
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
     */
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
        var response = invoke(config, endpoint, path, "POST", workspaceId, null);

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

        if (body != null && !body.equals("{}")) {
            sdkReqBuilder.withBodyAsString(body);
        }

        var sdkReq = sdkReqBuilder.build();

        // Sign with AK/SK if available; otherwise fall back to X-Auth-Token.
        var jdkReqBuilder = java.net.http.HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30));

        if (workspaceId != null && !workspaceId.isBlank()) {
            jdkReqBuilder.header("workspace", workspaceId);
        }

        if (config.accessKeyId() != null && !config.accessKeyId().isBlank()) {
            var signingCreds = new BasicCredentials()
                .withAk(config.accessKeyId())
                .withSk(config.secretAccessKey());
            if (config.securityToken() != null) {
                signingCreds.withSecurityToken(config.securityToken());
            }
            var signedHeaders = AKSKSigner.getInstance().sign(sdkReq, signingCreds);
            // JDK HttpRequest.Builder rejects restricted headers (Host, Connection, …) that the
            // signer adds for canonical request computation. The JDK sets Host automatically.
            signedHeaders.entrySet().stream()
                .filter(e -> !RESTRICTED_HEADERS.contains(e.getKey().toLowerCase()))
                .forEach(e -> jdkReqBuilder.header(e.getKey(), e.getValue()));
        } else if (config.securityToken() != null && !config.securityToken().isBlank()) {
            jdkReqBuilder.header("X-Auth-Token", config.securityToken());
        } else {
            throw new IllegalArgumentException(
                "DataArts Studio requires either AK/SK credentials (accessKeyId + secretAccessKey) " +
                "or a security token — configure at least one authentication method.");
        }

        // POST with no body still needs a publisher; noBody() works for GET.
        var bodyPublisher = (body != null && !body.equals("{}"))
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
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
