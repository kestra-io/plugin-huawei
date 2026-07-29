package io.kestra.plugin.huawei.dataarts;

import io.kestra.plugin.huawei.AbstractConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live regression test for {@code GET /v2/{project_id}/factory/jobs/{job_name}/instances/{id}} — the
 * route {@link DataArtsService#getInstance} calls, and the one route in the supplement-data rework
 * that the WireMock suite cannot vouch for on its own.
 *
 * <p><b>Why it exists.</b> A PR review flagged this route as probably unpublished, on the grounds that
 * it appears nowhere in the SDK's {@code DataArtsStudioMeta} metadata — the same evidence that
 * correctly predicted {@code run-immediate} was unusable. A live probe on {@code ap-southeast-3} (job
 * {@code job_6336}, instance {@code 1479208}, 2026-07-29) disproved that: the gateway serves it. The
 * inference was wrong in general, so metadata absence is corroborating evidence at best.
 *
 * <p>What the same probe <em>did</em> establish is asserted below, because none of it is reachable
 * from a WireMock stub — a stub only ever returns whatever shape the fixture author believed in, which
 * is exactly how the old full-shaped by-ID fixture hid the field gap for months:
 * <ol>
 *   <li>The route answers 200 for a real instance.</li>
 *   <li>The {@code {instance_id}} segment is <b>honoured</b>: a nonexistent ID fails with
 *       {@code DLF.30137} instead of falling back to the newest instance. Asserted with a
 *       deliberately-bogus ID, because requesting the newest instance's own ID — the obvious probe —
 *       returns an identical body whether the segment is honoured or ignored, and so proves
 *       nothing.</li>
 *   <li>The response is a strict <b>subset</b> of {@code instances/detail}'s: five fields are absent.
 *       If Huawei ever enriches it, this test fails and the docs claiming otherwise
 *       ({@code GetJobRun.instanceId}, {@code DataArtsService#getInstance}, AGENTS.md) need updating —
 *       which is the point of keeping this around rather than deleting it once the question was
 *       settled.</li>
 * </ol>
 *
 * Run with:
 * <pre>
 * DATAARTS_TESTS=true \
 * DATAARTS_ACCESS_KEY=... DATAARTS_SECRET_KEY=... \
 * DATAARTS_PROJECT_ID=... DATAARTS_WORKSPACE_ID=... \
 * DATAARTS_REGION=ap-southeast-3 DATAARTS_JOB_NAME=job_6336 \
 * ./gradlew test --tests '*GetInstanceRouteProbeTest*' -i
 * </pre>
 * {@code DATAARTS_JOB_NAME} must name a job that has already run at least once. Optionally set
 * {@code DATAARTS_ENDPOINT} to override the derived {@code dayu.<region>} host — required on
 * non-{@code .com} partitions, since {@link DataArtsUtils} has no {@code endpointSuffix} support.
 */
@EnabledIfEnvironmentVariable(named = "DATAARTS_TESTS", matches = "true")
class GetInstanceRouteProbeTest {

    /** Cannot exist: real instance IDs are seven digits (1479208 when this was written). */
    private static final long NONEXISTENT_INSTANCE_ID = 1L;

    private static String env(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + name + " is required by this probe");
        }
        return value;
    }

    private static String endpoint(String region) {
        var override = System.getenv("DATAARTS_ENDPOINT");
        return override != null && !override.isBlank()
            ? override
            : "https://dayu." + region + ".myhuaweicloud.com";
    }

    private static AbstractConnection.HuaweiClientConfig config(String region, String projectId) {
        return new AbstractConnection.HuaweiClientConfig(
            env("DATAARTS_ACCESS_KEY"),
            env("DATAARTS_SECRET_KEY"),
            System.getenv("DATAARTS_SECURITY_TOKEN"),
            projectId,
            null,
            region
        );
    }

    @Test
    void singleInstanceRoute_isPublished_honoursTheId_andReturnsASubsetOfTheListShape() throws Exception {
        var region = env("DATAARTS_REGION");
        var projectId = env("DATAARTS_PROJECT_ID");
        var workspaceId = env("DATAARTS_WORKSPACE_ID");
        var jobName = env("DATAARTS_JOB_NAME");
        var endpoint = endpoint(region);
        var config = config(region, projectId);

        // Control: instances/detail is verified-working and the only source of a real instance_id
        // (the console shows none, and it has no API of its own). A failure here means credentials,
        // workspace or signing are wrong, and nothing below would mean anything.
        var instances = DataArtsService.listInstancesFirstPage(config, endpoint, projectId, workspaceId, jobName, 10);
        var control = instances.stream().filter(i -> i.getInstanceId() != null).findFirst().orElse(null);
        if (control == null) {
            fail("Control step returned no instance carrying an instance_id — cannot probe the by-ID " +
                "route. Pick a DATAARTS_JOB_NAME that has already run at least once.");
        }

        // The list route populates the full model. Asserted so a regression here is distinguishable
        // from the by-ID route's own thinness below.
        assertNotNull(control.getJobInstanceName(), "instances/detail should return job_instance_name");
        assertNotNull(control.getJobId(), "instances/detail should return job_id");

        // 1. Published, and returns the instance that was asked for.
        var run = DataArtsService.getInstance(config, endpoint, projectId, workspaceId, jobName, control.getInstanceId());
        assertEquals(control.getInstanceId(), run.getInstanceId(), "by-ID route returned a different instance");
        assertNotNull(run.getStatus(), "by-ID route should return status");
        assertNotNull(run.getPlanTime(), "by-ID route should return plan_time");

        // 2. Strict subset of the list shape. These five are absent on the live route — end_time
        //    included, even for an instance already reporting success. If any becomes non-null,
        //    Huawei has enriched the response and the docs that warn about this are now wrong.
        assertNull(run.getJobInstanceName(), "by-ID route unexpectedly returned job_instance_name — " +
            "the response shape has changed; update GetJobRun's instanceId docs and AGENTS.md");
        assertNull(run.getEndTime(), "by-ID route unexpectedly returned end_time — response shape changed");
        assertNull(run.getSubmitTime(), "by-ID route unexpectedly returned submit_time — response shape changed");
        assertNull(run.getExecuteTime(), "by-ID route unexpectedly returned execute_time — response shape changed");
        assertNull(run.getJobId(), "by-ID route unexpectedly returned job_id — response shape changed");

        // 3. The {instance_id} segment is honoured rather than ignored.
        var ex = assertThrows(
            IllegalStateException.class,
            () -> DataArtsService.getInstance(
                config, endpoint, projectId, workspaceId, jobName, NONEXISTENT_INSTANCE_ID),
            "A nonexistent instance ID must fail. Succeeding would mean the {instance_id} segment is " +
                "ignored and the route just returns the latest instance — which would make " +
                "GetJobRun's instanceId property silently report the wrong run.");
        assertTrue(
            ex.getMessage().contains("DLF.30137"),
            "Expected DLF.30137 (\"Job instance does not exist.\") for a nonexistent instance, got: " +
                ex.getMessage());
    }
}
