package io.kestra.plugin.huawei.obs;

import io.kestra.core.utils.IdUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * THROWAWAY verification test for the CI cleanup backstop — DO NOT MERGE.
 *
 * <p>Verifies kestra-io/actions PR (the {@code !cancelled()} condition on the "Cleanup - Unit test"
 * step) together with the run-scoped {@code .github/cleanup-unit.sh}. It seeds one object under this
 * run's prefix and then halts the JVM, which:
 * <ul>
 *   <li>fails the {@code Test - Gradle Check} step (crashed test worker → non-zero exit), and</li>
 *   <li>skips the in-JVM {@code @AfterAll} sweep — so the seeded object survives, exactly the
 *       JVM/step-death scenario the CI cleanup script exists to cover.</li>
 * </ul>
 *
 * <p>Expected CI outcome on a branch pinned to the fixed workflow: {@code Test - Gradle Check} ❌,
 * {@code Cleanup - Unit test} ✅ (runs despite the failure), and its log shows
 * {@code delete: s3://kestra-unit-test/it/<GITHUB_RUN_ID>/...} for the seeded key printed below.
 */
@EnabledIfEnvironmentVariable(named = "OBS_MINIO_TESTS", matches = "true")
class LeakBackstopVerifyTest extends AbstractObsTest {

    @Test
    void seedsThenHaltsJvm_soOnlyTheCiCleanupScriptCanReclaimTheObject() {
        var leakedKey = key("leak-" + IdUtils.create() + ".txt");
        seedObject(leakedKey, "leak-verify", "text/plain");

        // Printed to the Gradle step log so we can match it against the cleanup step's delete output.
        System.out.println("LEAK_VERIFY seeded key: " + leakedKey);
        System.out.flush();

        // Kill the JVM hard: no @AfterAll, no shutdown hooks — the object is left in the bucket and the
        // Gradle step goes red. The CI "Cleanup - Unit test" step must then delete it.
        Runtime.getRuntime().halt(37);
    }
}
