package io.kestra.plugin.huawei.dataarts;

import io.kestra.plugin.huawei.AbstractConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live probe settling whether {@code GET /v2/{project_id}/factory/jobs/{job_name}/instances/{id}}
 * — the route {@link DataArtsService#getInstance} calls, and the only route in the supplement-data
 * rework that was never live-verified — is actually published on the API gateway.
 *
 * <p>This has to be an <b>authenticated</b> call. An unauthenticated probe cannot tell a working
 * route from a dead one on this gateway: {@code run-immediate} answers {@code APIGW.0301}
 * ("Incorrect IAM authentication information", i.e. the gateway does route it) while still failing
 * {@code DLF.3051} once authenticated.
 *
 * <p>Two steps, because a valid numeric {@code instance_id} is not obtainable anywhere else — it is
 * not shown in the console and has no API of its own:
 * <ol>
 *   <li>{@code instances/detail} (a verified-working route) to obtain a real {@code instance_id}.
 *       This doubles as a control: if it fails, credentials/workspace/signing are wrong and step 2's
 *       result would be meaningless.</li>
 *   <li>{@code instances/{id}} with that ID. Read the outcome as:
 *       <ul>
 *         <li><b>HTTP 200</b> — route is published; finding 1 is a false alarm.</li>
 *         <li><b>HTTP 404 {@code APIGW.0101}</b> — route does not exist; {@code GetJobRun}'s
 *             {@code instanceId} branch is dead and needs a redesign.</li>
 *         <li><b>any {@code DLF.*} error</b> — route exists (a domain-level complaint means signing,
 *             routing, headers and body all passed); investigate the specific code.</li>
 *         <li><b>{@code APIGW.0301}</b> — inconclusive; credentials problem, fix and re-run.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * Run with:
 * <pre>
 * DATAARTS_TESTS=true \
 * DATAARTS_ACCESS_KEY=... DATAARTS_SECRET_KEY=... \
 * DATAARTS_PROJECT_ID=... DATAARTS_WORKSPACE_ID=... \
 * DATAARTS_REGION=ap-southeast-3 DATAARTS_JOB_NAME=job_8139 \
 * ./gradlew test --tests '*GetInstanceRouteProbeTest*' -i
 * </pre>
 * Optionally set {@code DATAARTS_ENDPOINT} to override the derived {@code dayu.<region>} host
 * (required on non-{@code .com} partitions — {@link DataArtsUtils} has no {@code endpointSuffix}).
 */
@EnabledIfEnvironmentVariable(named = "DATAARTS_TESTS", matches = "true")
class GetInstanceRouteProbeTest {

    private static String env(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + name + " is required by this probe");
        }
        return value;
    }

    @Test
    void singleInstanceByIdRouteIsPublished() throws Exception {
        var region = env("DATAARTS_REGION");
        var projectId = env("DATAARTS_PROJECT_ID");
        var workspaceId = env("DATAARTS_WORKSPACE_ID");
        var jobName = env("DATAARTS_JOB_NAME");

        var endpointOverride = System.getenv("DATAARTS_ENDPOINT");
        var endpoint = endpointOverride != null && !endpointOverride.isBlank()
            ? endpointOverride
            : "https://dayu." + region + ".myhuaweicloud.com";

        var config = new AbstractConnection.HuaweiClientConfig(
            env("DATAARTS_ACCESS_KEY"),
            env("DATAARTS_SECRET_KEY"),
            System.getenv("DATAARTS_SECURITY_TOKEN"),
            projectId,
            null,
            region
        );

        // Step 1 — control. Verified-working route; also the only source of a real instance_id.
        var instances = DataArtsService.listInstancesFirstPage(config, endpoint, projectId, workspaceId, jobName, 10);
        System.out.println("[probe] instances/detail returned " + instances.size() + " instance(s) for job '" + jobName + "'");
        instances.forEach(i -> System.out.println(
            "[probe]   instance_id=" + i.getInstanceId() +
            " name=" + i.getJobInstanceName() +
            " status=" + i.getStatus()));

        var withId = instances.stream().filter(i -> i.getInstanceId() != null).findFirst();
        if (withId.isEmpty()) {
            fail("Control step returned no instance carrying an instance_id — cannot probe the by-ID route. " +
                "Pick a DATAARTS_JOB_NAME that has already run at least once.");
        }
        var instanceId = withId.get().getInstanceId();

        // Step 2 — the route under test.
        try {
            var run = DataArtsService.getInstance(config, endpoint, projectId, workspaceId, jobName, instanceId);
            System.out.println("[probe] VERDICT: route IS published (HTTP 200) — instance_id=" + run.getInstanceId() +
                " status=" + run.getStatus() + " jobInstanceName=" + run.getJobInstanceName());
        } catch (IllegalStateException e) {
            var message = e.getMessage() == null ? "" : e.getMessage();
            System.out.println("[probe] VERDICT: route call FAILED — " + message);
            assertFalse(
                message.contains("APIGW.0101"),
                "GET .../factory/jobs/{job}/instances/{id} is NOT published on the API gateway " +
                    "(APIGW.0101). GetJobRun's instanceId branch cannot work and needs a redesign — " +
                    "e.g. fetch instances/detail and match instance_id client-side, the way " +
                    "getSupplementData matches by name. Full error: " + message);
            throw e;
        }
    }
}
