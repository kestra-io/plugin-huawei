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
import io.kestra.plugin.huawei.dataarts.models.SupplementDataRun;
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

    // The API gateway declares Content-Type as a required header on the factory POST routes.
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String JSON_CONTENT_TYPE = "application/json";

    private DataArtsService() {
    }

    /**
     * Terminal states: the job run will not advance further once in one of these states.
     * Returns {@code false} for {@code null} status (freshly-queued instances whose status
     * field has not yet been populated by the API).
     *
     * <p>The status enum, taken from the SDK's {@code JobInstance$StatusEnum}, is exactly:
     * {@code waiting}, {@code running}, {@code success}, {@code fail}, {@code manual},
     * {@code pause}, {@code skip}, {@code freeze}. Of those, {@code success}, {@code fail} and
     * {@code skip} are terminal; {@code waiting}/{@code running} are in flight, and
     * {@code manual}/{@code pause}/{@code freeze} await an operator, so polling them until
     * {@code maxDuration} (rather than returning) is the intended behaviour.
     *
     * <p>{@code forceSuccess} and {@code ignoreSuccess} are deliberately absent: they are separate
     * <em>boolean</em> fields on the instance, never status values, so matching them here was dead
     * code. The hyphenated spellings previously listed ({@code skip-by-depend},
     * {@code running-exception}, {@code manual-stop}) are not in the enum either — {@code skip} is
     * the real spelling, and treating it as non-terminal made a skipped instance poll until timeout.
     */
    public static boolean isTerminalState(String status) {
        if (status == null) return false;
        return switch (status) {
            case "success", "fail", "skip" -> true;
            default -> false;
        };
    }

    /**
     * Successful terminal state — job completed without error. {@code skip} is terminal but not a
     * success: the instance never ran, so callers should surface it rather than treat it as done.
     */
    public static boolean isSuccessState(String status) {
        return "success".equals(status);
    }

    /**
     * Terminal states for a <em>supplement-data</em> (PatchData) run.
     *
     * <p>Supplement-data statuses are <b>upper case</b> — {@code SUCCESS}, {@code RUNNING},
     * {@code CANCEL} per the {@code status} filter documented on
     * <a href="https://support.huaweicloud.com/intl/en-us/api-dataartsstudio/dataartsstudio_02_0192.html">
     * Querying PatchData Instances</a>, and the response example there returns {@code "RUNNING"}.
     * They are <em>not</em> the lower-case vocabulary a plain job instance uses
     * ({@link #isTerminalState}), so the two must not be compared with each other.
     *
     * <p>Matching is case-insensitive and covers plausible spellings of the failure and cancellation
     * states, since the doc enumerates only the three values accepted as a query filter and no SDK
     * enum exists ({@code SupplementDataRespRows#status} is a plain {@code String}). An
     * unrecognised status is treated as non-terminal, so the worst case is polling until
     * {@code maxDuration} rather than reporting a wrong outcome; callers log every observed status at
     * INFO so any value missing from this set can be recovered from a run's logs.
     */
    public static boolean isSupplementDataTerminalState(String status) {
        if (status == null) return false;
        return switch (status.toUpperCase()) {
            case "SUCCESS", "FAIL", "FAILED", "CANCEL", "CANCELED", "CANCELLED", "STOP", "STOPPED" -> true;
            default -> false;
        };
    }

    /**
     * Successful terminal state for a supplement-data run. Upper case on the wire — see
     * {@link #isSupplementDataTerminalState}.
     */
    public static boolean isSupplementDataSuccessState(String status) {
        return status != null && "SUCCESS".equalsIgnoreCase(status);
    }

    /**
     * Creates a supplement-data (PatchData) instance, which is how this plugin triggers an
     * on-demand job run.
     *
     * <p><b>Why supplement-data and not a job-trigger route</b>: DataArts Factory publishes no
     * usable job-trigger API. {@code POST .../jobs/{name}/run-immediate} rejects every request body
     * with {@code DLF.3051 "The request parameter is invalid."} — verified live on both
     * {@code tr-west-1} (T-Systems EU sovereign) and {@code ap-southeast-3} (standard {@code .com}),
     * on run-once and scheduled jobs alike, so it is not partition-specific. {@code POST
     * .../jobs/{name}/start} only toggles a schedule and also returns {@code DLF.3051} on a run-once
     * job. Neither route appears in {@code DataArtsStudioMeta}'s {@code HttpRequestDef} metadata at
     * any API version: the complete {@code factory/jobs} route set is {@code jobs},
     * {@code jobs/{job_name}/instances/detail}, {@code .../instances/retry}, {@code .../rename} and
     * {@code .../tags}. The console's own Execute button uses the session-authenticated
     * {@code /v1.0/{ws}/pipelines/run-pipelines} route, which 404s ({@code APIGW.0101}) on the public
     * AK/SK gateway.
     *
     * <p>{@code POST /v2/{project_id}/factory/supplement-data} <em>is</em> declared in that metadata,
     * together with a {@code GET} for status and a {@code POST .../{instance_name}/stop} — a complete
     * create/poll/cancel triad. It also avoids run-immediate's resolve-the-new-instance race, because
     * the caller supplies the instance {@code name} in the request body and can query it back
     * verbatim.
     *
     * <p><b>Semantic caveat</b>: supplement-data is a <em>backfill</em> mechanism — it re-runs a job
     * over a range of business dates. Running it over a single day is effectively "run now" for a
     * date-parameterised job, but the resulting run appears in the console's Supplement Data
     * monitoring view, not Job Monitoring, and is not a plain job instance.
     *
     * <p>{@code start_date}/{@code end_date} must already be in the {@code 2026-07-28T00:00:00 +00}
     * form DataArts parses; the caller normalises them. See
     * {@code StartJobRun.toWireDateTime}.
     *
     * @param name              caller-chosen instance name; used later to poll and to stop the run
     * @param jobName           job to run
     * @param startDate         start of the business-date range, in DataArts' wire form
     * @param endDate           end of the business-date range, in DataArts' wire form
     * @param parallel          number of instances to execute concurrently (null → omitted)
     * @param dayGranularity    whether the range is day-granular (null → omitted)
     * @param stopWhenFail      whether to abort remaining instances after a failure (null → omitted)
     * @param runTimeWindow     {@code HH:mm-HH:mm} window the run is allowed to execute in
     *                          (null → omitted, which means the API's own {@code 00:00-00:00} default)
     */
    public static void createSupplementData(
        RunContext runContext,
        AbstractConnection.HuaweiClientConfig config,
        String endpoint,
        String projectId,
        @Nullable String workspaceId,
        String name,
        String jobName,
        String startDate,
        String endDate,
        @Nullable Integer parallel,
        @Nullable Boolean dayGranularity,
        @Nullable Boolean stopWhenFail,
        @Nullable String runTimeWindow
    ) throws Exception {
        var path = "/v2/" + projectId + "/factory/supplement-data";

        var bodyMap = new LinkedHashMap<String, Object>();
        bodyMap.put("name", name);
        bodyMap.put("job_name", jobName);
        bodyMap.put("start_date", startDate);
        bodyMap.put("end_date", endDate);
        if (parallel != null) bodyMap.put("parallel", parallel);
        if (dayGranularity != null) bodyMap.put("is_day_granularity", dayGranularity);
        if (stopWhenFail != null) bodyMap.put("is_stop_when_fail", stopWhenFail);
        // Omitted unless the caller asks for a window. The API's documented default of 00:00-00:00
        // reads like a zero-width window that would never execute, but a live run with the field
        // absent executed all of its instances immediately, so it evidently means "unrestricted".
        if (runTimeWindow != null) {
            bodyMap.put("supplement_data_run_time", Map.of("time_of_day", runTimeWindow));
        }

        var body = JacksonMapper.ofJson().writeValueAsString(bodyMap);
        var response = invoke(config, endpoint, path, "POST", workspaceId, body);

        if (response.statusCode() != 204 && response.statusCode() != 200) {
            var detail = parseDlfError(response.body());
            String hint;
            if (detail.contains("DLF.30111")) {
                hint = " — supplement-data only accepts a job that has a trigger: a cron schedule, an HTTP"
                    + " trigger, or a parent job. A run-once / manually-triggered job is rejected. Since"
                    + " DataArts publishes no other working job-trigger route, give the job a schedule in"
                    + " the DataArts Studio console (Job Monitoring → the job → scheduling), then retry."
                    + " Note the schedule need not fire on its own for this task to work — it only has to"
                    + " exist.";
            } else if (detail.contains("DLF.30121")) {
                hint = " — startDate and endDate are compared as timestamps and must be at least 2 seconds"
                    + " apart. Note DataArts reports this same error for a value it could not parse at"
                    + " all, because both ends then collapse to the same instant: check that the dates"
                    + " sent (logged just above) are in the form 'yyyy-MM-ddTHH:mm:ss +00' that DataArts"
                    + " requires, and not a form passed through verbatim from startDate/endDate.";
            } else if (detail.contains("DLF.3051")) {
                hint = " — DLF rejected the request body. Confirm the job name exists in the target"
                    + " workspace, and that startDate/endDate are in the form"
                    + " 'yyyy-MM-ddTHH:mm:ss +00' that DataArts requires.";
            } else {
                hint = " — check that the job name exists in this workspace, that the instance name is unique,"
                    + " and that the credentials have permission to submit supplement data.";
            }
            throw new IllegalStateException(
                "DataArts Factory create supplement-data run '" + name + "' for job '" + jobName +
                "' failed (HTTP " + response.statusCode() + ")" + detail + hint);
        }
        runContext.logger().debug("Supplement-data run '{}' for job '{}' accepted (HTTP {})",
            name, jobName, response.statusCode());
    }

    /**
     * Fetches a supplement-data run by its caller-chosen {@code name}, or {@code null} when no run
     * with that exact name exists yet.
     *
     * <p>The {@code name} query parameter is treated as a filter by the API rather than an exact
     * key, so the returned rows are matched on {@code name} client-side — a prefix-matching
     * implementation would otherwise be able to return a different run's status.
     *
     * <p>⚠ {@code page} is <b>0-based</b> (documented as "default 0, must be ≥ 0"), unlike the
     * 1-based {@code offset} conventions elsewhere in Huawei's APIs. Requesting {@code page=1}
     * asks for the <em>second</em> page, which for a name-filtered query returns an empty
     * {@code rows} array — indistinguishable from "the run does not exist", which is exactly how it
     * presented: every {@code StartJobRun} timed out waiting for a run that had been created
     * successfully.
     */
    @Nullable
    public static SupplementDataRun getSupplementData(
        @Nullable RunContext runContext,
        AbstractConnection.HuaweiClientConfig config,
        String endpoint,
        String projectId,
        @Nullable String workspaceId,
        String name
    ) throws Exception {
        var path = "/v2/" + projectId + "/factory/supplement-data"
            + "?name=" + urlEncode(name)
            + "&page=0&size=100";

        var response = invoke(config, endpoint, path, "GET", workspaceId, null);

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "DataArts Factory list supplement-data runs failed (HTTP " + response.statusCode() + ")" +
                parseDlfError(response.body()) +
                " — verify the workspace and that the credentials have permission to read supplement data.");
        }

        var body = JacksonMapper.ofJson().readTree(response.body());
        var rows = body.path("rows");
        if (rows.isMissingNode() || !rows.isArray()) {
            return null;
        }
        for (var row : rows) {
            if (name.equals(textOrNull(row, "name"))) {
                return nodeToSupplementDataRun(row);
            }
        }

        // A miss is ambiguous — the run may not exist yet, or the query may be wrong (a 0-based
        // `page` off-by-one silently returned an empty page for months). Reporting what the API
        // actually returned makes the difference visible without another rebuild.
        if (runContext != null) {
            var names = new ArrayList<String>();
            rows.forEach(row -> names.add(String.valueOf(textOrNull(row, "name"))));
            runContext.logger().debug(
                "Supplement-data query for name='{}' returned total={}, {} row(s): {}",
                name, body.path("total").asText("?"), names.size(), names);
        }
        return null;
    }

    /**
     * Sends a stop request for a supplement-data run, identified by the {@code name} it was created
     * with.
     *
     * <p>This is the only stop route DataArts Factory publishes: there is no stop operation for a
     * plain job instance. All of
     * {@code POST|PUT /v2/{pid}/factory/jobs/{job}/instances/{id}/stop},
     * {@code POST /v2/{pid}/factory/jobs/{job}/stop},
     * {@code POST /v2/{pid}/factory/jobs/{job}/instances/stop} and
     * {@code POST /v2/{pid}/factory/jobs/instances/{id}/stop} return {@code APIGW.0101} (not
     * published), and {@code DataArtsStudioMeta} declares no factory-job stop route. Consequently a
     * run triggered from the console cannot be stopped through this plugin — only one created by
     * {@link #createSupplementData}.
     */
    public static void stopSupplementData(
        RunContext runContext,
        AbstractConnection.HuaweiClientConfig config,
        String endpoint,
        String projectId,
        @Nullable String workspaceId,
        String name
    ) throws Exception {
        var path = "/v2/" + projectId + "/factory/supplement-data/" + urlEncode(name) + "/stop";
        var response = invoke(config, endpoint, path, "POST", workspaceId, "{}");

        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw new IllegalStateException(
                "DataArts Factory stop supplement-data run '" + name + "' failed (HTTP " +
                response.statusCode() + ")" + parseDlfError(response.body()) +
                " — check that the run exists and is still in a stoppable state.");
        }
        runContext.logger().debug("Stop supplement-data run '{}' accepted (HTTP {})", name, response.statusCode());
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

    // ── Internal helpers ─────────────────────────────────────────────────────────

    /**
     * Maps one {@code supplement-data} {@code rows[]} element onto {@link SupplementDataRun}.
     *
     * <p>Wire format is snake_case, per the {@code @JsonProperty} names on the SDK's
     * {@code SupplementDataRespRows}: {@code name}, {@code job_list}, {@code status},
     * {@code start_date}, {@code end_date}, {@code submitted_date}, {@code parallel}, {@code type},
     * {@code user_name}. camelCase spellings are kept as fallbacks, as in {@link #nodeToJobRun}.
     */
    private static SupplementDataRun nodeToSupplementDataRun(JsonNode node) {
        var jobList = new ArrayList<String>();
        var jobListNode = firstPresent(node, "job_list", "jobList");
        if (jobListNode != null && jobListNode.isArray()) {
            jobListNode.forEach(j -> jobList.add(j.asText()));
        }

        return SupplementDataRun.builder()
            .name(textOrNull(node, "name"))
            .jobList(jobList)
            .status(textOrNull(node, "status"))
            .startDate(longOrNull(node, "start_date", "startDate"))
            .endDate(longOrNull(node, "end_date", "endDate"))
            .submittedDate(longOrNull(node, "submitted_date", "submittedDate"))
            .parallel(intOrNull(node, "parallel"))
            .type(intOrNull(node, "type"))
            .userName(textOrNull(node, "user_name", "userName"))
            .build();
    }

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
            // AKSKSigner does not always echo Content-Type back in its header map, even though
            // withContentType() above feeds it into the canonical request. When it doesn't, nothing
            // else sets the header and the API gateway rejects the call before DLF ever sees it:
            // `APIGW.0106 "Invalid header parameter: Content-Type, required"` on POST
            // /v2/{pid}/factory/supplement-data. Supplying it only when absent keeps exactly one
            // value on the wire — a second, duplicate value would be comma-joined into
            // "application/json, application/json" and break signature verification.
            if (!containsHeaderIgnoreCase(signedHeaders, CONTENT_TYPE_HEADER)) {
                jdkReqBuilder.header(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE);
            }
        } else if (hasSecurityToken) {
            jdkReqBuilder.header("X-Auth-Token", securityToken);
            // Nothing is signed on this path, so the header is set unconditionally — the gateway
            // requires it on POST regardless of the authentication method.
            jdkReqBuilder.header(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE);
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

    /**
     * Case-insensitive header-name lookup. HTTP header names are case-insensitive, and the SDK's
     * signer is not guaranteed to use any particular casing, so a plain {@code containsKey} would
     * miss a {@code content-type} entry and produce a duplicate header.
     */
    private static boolean containsHeaderIgnoreCase(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(name));
    }

    /**
     * Maps one {@code instances/detail} array element onto {@link JobRun}.
     *
     * <p>The wire format is <b>snake_case</b> — verified against the {@code @JsonProperty} names on
     * the SDK's own {@code JobInstance} model: {@code instance_id}, {@code job_instance_name},
     * {@code plan_time}, {@code start_time}, {@code end_time}, {@code execute_time},
     * {@code submit_time}, {@code job_id}, {@code status}. Reading camelCase keys here silently
     * yielded {@code null} for every field except {@code status} (spelled identically in both
     * conventions), which made every task emit a two-field output and left
     * {@code StartJobRun.resolveNewestInstance} unable to ever match its {@code instanceId >
     * waterMark} filter. The camelCase spellings are kept as fallbacks so a route that does return
     * them still maps.
     *
     * <p>{@code jobName} comes from the caller rather than the payload: the single-instance route
     * does not echo it back, and the caller always knows it.
     */
    private static JobRun nodeToJobRun(String jobName, JsonNode node) {
        return JobRun.builder()
            .jobName(jobName)
            .instanceId(longOrNull(node, "instance_id", "instanceId"))
            .jobInstanceName(textOrNull(node, "job_instance_name", "jobInstanceName"))
            .status(textOrNull(node, "status"))
            .planTime(longOrNull(node, "plan_time", "planTime"))
            .startTime(longOrNull(node, "start_time", "startTime"))
            .endTime(longOrNull(node, "end_time", "endTime"))
            .executeTime(longOrNull(node, "execute_time", "executeTime"))
            .submitTime(longOrNull(node, "submit_time", "submitTime"))
            .jobId(longOrNull(node, "job_id", "jobId"))
            .build();
    }

    /**
     * Returns the first of {@code fields} that is present and non-null, as text; null if none match.
     */
    private static String textOrNull(JsonNode node, String... fields) {
        var n = firstPresent(node, fields);
        return n == null ? null : n.asText();
    }

    /**
     * Returns the first of {@code fields} that is present and non-null, as a long; null if none match.
     */
    private static Long longOrNull(JsonNode node, String... fields) {
        var n = firstPresent(node, fields);
        return n == null ? null : n.asLong();
    }

    /**
     * Returns the first of {@code fields} that is present and non-null, as an int; null if none match.
     */
    private static Integer intOrNull(JsonNode node, String... fields) {
        var n = firstPresent(node, fields);
        return n == null ? null : n.asInt();
    }

    private static JsonNode firstPresent(JsonNode node, String... fields) {
        for (var field : fields) {
            var n = node.path(field);
            if (!n.isMissingNode() && !n.isNull()) {
                return n;
            }
        }
        return null;
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
