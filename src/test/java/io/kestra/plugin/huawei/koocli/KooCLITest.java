package io.kestra.plugin.huawei.koocli;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.huawei.AbstractConnection;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class KooCLITest {

    private static final String FAKE_AK = "FAKEACCESSKEY0001";
    private static final String FAKE_SK = "fakeSecretKey0001fakeSecretKey001";

    @Inject
    RunContextFactory runContextFactory;

    // ── Unit tests (no Docker, no network) ──────────────────────────────────────

    @Test
    void defaults_containerImageIsUbuntu() throws Exception {
        var task = KooCLI.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .commands(Property.ofValue(List.of("hcloud version")))
            .build();
        var runContext = runContextFactory.of(Collections.emptyMap());

        assertThat(runContext.render(task.getContainerImage()).as(String.class).orElseThrow(), equalTo("ubuntu:26.04"));
    }

    @Test
    void customContainerImage_isPreserved() throws Exception {
        var task = KooCLI.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .commands(Property.ofValue(List.of("hcloud version")))
            .containerImage(Property.ofValue("my-registry/hcloud-ready:1.0"))
            .build();
        var runContext = runContextFactory.of(Collections.emptyMap());

        assertThat(runContext.render(task.getContainerImage()).as(String.class).orElseThrow(), equalTo("my-registry/hcloud-ready:1.0"));
    }

    // resolveInstallScriptUrl is private static; invoked via reflection to unit-test the 3-tier
    // resolution logic without requiring Docker/network (real hcloud install).
    private static String resolveInstallScriptUrl(String override, String region) throws Exception {
        Method method = KooCLI.class.getDeclaredMethod("resolveInstallScriptUrl", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, override, region);
    }

    @Test
    void resolveInstallScriptUrl_standardRegion_fallsBackToInternational() throws Exception {
        assertThat(resolveInstallScriptUrl(null, "ap-southeast-3"), equalTo(
            "https://ap-southeast-3-hwcloudcli.obs.ap-southeast-3.myhuaweicloud.com/cli/latest/hcloud_install.sh"));
    }

    @Test
    void resolveInstallScriptUrl_unknownRegion_fallsBackToInternational() throws Exception {
        assertThat(resolveInstallScriptUrl(null, null), equalTo(
            "https://ap-southeast-3-hwcloudcli.obs.ap-southeast-3.myhuaweicloud.com/cli/latest/hcloud_install.sh"));
    }

    @Test
    void resolveInstallScriptUrl_euSovereignRegion_autoSelectsEuBinary() throws Exception {
        assertThat(resolveInstallScriptUrl(null, "eu-west-101"), equalTo(
            "https://eu-west-101-apiexplorer-cli.obs.eu-west-101.myhuaweicloud.eu/cli/latest/hcloud_install.sh"));
    }

    @Test
    void resolveInstallScriptUrl_explicitOverride_alwaysWinsOverSovereignRegion() throws Exception {
        var hcsUrl = "https://hcloudcli.my-hcs-domain.example.com/cli/latest/hcloud_install.sh";

        assertThat(resolveInstallScriptUrl(hcsUrl, "eu-west-101"), equalTo(hcsUrl));
    }

    @Test
    void resolveInstallScriptUrl_explicitOverride_alwaysWinsOverStandardRegion() throws Exception {
        var hcsUrl = "https://hcloudcli.my-hcs-domain.example.com/cli/latest/hcloud_install.sh";

        assertThat(resolveInstallScriptUrl(hcsUrl, "ap-southeast-3"), equalTo(hcsUrl));
    }

    @Test
    void defaults_installScriptUrlPropertyIsUnset() {
        var task = KooCLI.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .commands(Property.ofValue(List.of("hcloud version")))
            .build();

        assertThat(task.getInstallScriptUrl(), is(nullValue()));
    }

    @Test
    void customEnv_isStoredOnTask() throws Exception {
        var task = KooCLI.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .commands(Property.ofValue(List.of("hcloud version")))
            .env(Property.ofValue(Map.of("MY_VAR", "hello")))
            .build();

        var runContext = runContextFactory.of(Collections.emptyMap());

        assertThat(runContext.render(task.getEnv()).asMap(String.class, String.class), hasEntry("MY_VAR", "hello"));
    }

    @Test
    void defaults_taskRunnerIsDocker() {
        var task = KooCLI.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .commands(Property.ofValue(List.of("hcloud version")))
            .build();

        assertThat(task.getTaskRunner(), is(notNullValue()));
    }

    // ── buildConfigureCommand (reflection; private instance method) ──────────────

    private static String buildConfigureCommand(KooCLI task, AbstractConnection.HuaweiClientConfig clientConfig) throws Exception {
        Method method = KooCLI.class.getDeclaredMethod("buildConfigureCommand", AbstractConnection.HuaweiClientConfig.class);
        method.setAccessible(true);
        return (String) method.invoke(task, clientConfig);
    }

    @Test
    void buildConfigureCommand_withAllFields_includesSecurityToken() throws Exception {
        var task = KooCLI.builder().build();
        var clientConfig = new AbstractConnection.HuaweiClientConfig(
            FAKE_AK, FAKE_SK, "fake-token", null, null, "eu-west-101");

        var command = buildConfigureCommand(task, clientConfig);

        assertThat(command, containsString("hcloud configure set --cli-profile=default"));
        assertThat(command, containsString("--cli-access-key=\"$HUAWEICLOUD_SDK_AK\""));
        assertThat(command, containsString("--cli-secret-key=\"$HUAWEICLOUD_SDK_SK\""));
        assertThat(command, containsString("--cli-security-token=\"$HUAWEICLOUD_SDK_SECURITY_TOKEN\""));
        assertThat(command, containsString("--cli-region=\"$HUAWEICLOUD_SDK_REGION\""));
    }

    @Test
    void buildConfigureCommand_withCredentials_failsFastOnConfigureError() throws Exception {
        var task = KooCLI.builder().build();
        var clientConfig = new AbstractConnection.HuaweiClientConfig(
            FAKE_AK, FAKE_SK, null, null, null, "eu-west-101");

        var command = buildConfigureCommand(task, clientConfig);

        assertThat(command, endsWith("|| { echo \"hcloud configure set failed\" >&2; exit 1; }"));
    }

    @Test
    void buildConfigureCommand_withoutSecurityToken_omitsSecurityTokenFlag() throws Exception {
        var task = KooCLI.builder().build();
        var clientConfig = new AbstractConnection.HuaweiClientConfig(
            FAKE_AK, FAKE_SK, null, null, null, "eu-west-101");

        var command = buildConfigureCommand(task, clientConfig);

        assertThat(command, not(containsString("--cli-security-token")));
        assertThat(command, containsString("--cli-access-key=\"$HUAWEICLOUD_SDK_AK\""));
    }

    @Test
    void buildConfigureCommand_withNoCredentials_returnsNull() throws Exception {
        var task = KooCLI.builder().build();
        var clientConfig = new AbstractConnection.HuaweiClientConfig(null, null, null, null, null, null);

        assertThat(buildConfigureCommand(task, clientConfig), is(nullValue()));
    }

    // ── buildEnv (reflection; private instance method) ───────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, String> buildEnv(KooCLI task, io.kestra.core.runners.RunContext runContext, AbstractConnection.HuaweiClientConfig clientConfig) throws Exception {
        Method method = KooCLI.class.getDeclaredMethod("buildEnv", io.kestra.core.runners.RunContext.class, AbstractConnection.HuaweiClientConfig.class);
        method.setAccessible(true);
        return (Map<String, String>) method.invoke(task, runContext, clientConfig);
    }

    @Test
    void buildEnv_injectsCredentials() throws Exception {
        var task = KooCLI.builder()
            .commands(Property.ofValue(List.of("hcloud version")))
            .build();
        var runContext = runContextFactory.of(Collections.emptyMap());
        var clientConfig = new AbstractConnection.HuaweiClientConfig(
            FAKE_AK, FAKE_SK, "fake-token", null, null, "eu-west-101");

        var env = buildEnv(task, runContext, clientConfig);

        assertThat(env, hasEntry("HUAWEICLOUD_SDK_AK", FAKE_AK));
        assertThat(env, hasEntry("HUAWEICLOUD_SDK_SK", FAKE_SK));
        assertThat(env, hasEntry("HUAWEICLOUD_SDK_SECURITY_TOKEN", "fake-token"));
        assertThat(env, hasEntry("HUAWEICLOUD_SDK_REGION", "eu-west-101"));
    }

    @Test
    void buildEnv_mergesUserSuppliedVariables() throws Exception {
        var task = KooCLI.builder()
            .commands(Property.ofValue(List.of("hcloud version")))
            .env(Property.ofValue(Map.of("MY_VAR", "hello")))
            .build();
        var runContext = runContextFactory.of(Collections.emptyMap());
        var clientConfig = new AbstractConnection.HuaweiClientConfig(
            FAKE_AK, FAKE_SK, null, null, null, "eu-west-101");

        var env = buildEnv(task, runContext, clientConfig);

        assertThat(env, hasEntry("MY_VAR", "hello"));
        assertThat(env, hasEntry("HUAWEICLOUD_SDK_AK", FAKE_AK));
    }

    @Test
    void buildEnv_userVariableCollidingWithCredentialKey_injectedValueWins() throws Exception {
        var task = KooCLI.builder()
            .commands(Property.ofValue(List.of("hcloud version")))
            .env(Property.ofValue(Map.of("HUAWEICLOUD_SDK_AK", "attacker-supplied-value")))
            .build();
        var runContext = runContextFactory.of(Collections.emptyMap());
        var clientConfig = new AbstractConnection.HuaweiClientConfig(
            FAKE_AK, FAKE_SK, null, null, null, "eu-west-101");

        var env = buildEnv(task, runContext, clientConfig);

        assertThat(env, hasEntry("HUAWEICLOUD_SDK_AK", FAKE_AK));
    }

    // ── buildInstallCommand / URL validation (reflection; private static methods) ─

    private static String buildInstallCommand(String url) throws Exception {
        Method method = KooCLI.class.getDeclaredMethod("buildInstallCommand", String.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(null, url);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    @Test
    void buildInstallCommand_withLegitInternationalUrl_isAccepted() throws Exception {
        var url = "https://ap-southeast-3-hwcloudcli.obs.ap-southeast-3.myhuaweicloud.com/cli/latest/hcloud_install.sh";

        var command = buildInstallCommand(url);

        assertThat(command, containsString("curl -sSL '" + url + "' | bash -s -- -y"));
    }

    @Test
    void buildInstallCommand_withLegitSovereignUrl_isAccepted() throws Exception {
        var url = "https://eu-west-101-apiexplorer-cli.obs.eu-west-101.myhuaweicloud.eu/cli/latest/hcloud_install.sh";

        var command = buildInstallCommand(url);

        assertThat(command, containsString("curl -sSL '" + url + "' | bash -s -- -y"));
    }

    @Test
    void buildInstallCommand_withCommandSubstitutionPayload_isRejected() {
        var maliciousUrl = "https://x/$(touch /tmp/pwned)";

        assertThrows(IllegalArgumentException.class, () -> buildInstallCommand(maliciousUrl));
    }

    @Test
    void buildInstallCommand_withSemicolonPayload_isRejected() {
        var maliciousUrl = "https://x/;touch /tmp/pwned";

        assertThrows(IllegalArgumentException.class, () -> buildInstallCommand(maliciousUrl));
    }

    @Test
    void buildInstallCommand_withBacktickPayload_isRejected() {
        var maliciousUrl = "https://x/`touch /tmp/pwned`";

        assertThrows(IllegalArgumentException.class, () -> buildInstallCommand(maliciousUrl));
    }

    @Test
    void buildInstallCommand_withSingleQuotePayload_isRejected() {
        var maliciousUrl = "https://x/'; touch /tmp/pwned; echo '";

        assertThrows(IllegalArgumentException.class, () -> buildInstallCommand(maliciousUrl));
    }

    @Test
    void buildInstallCommand_withNonHttpsScheme_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> buildInstallCommand("ftp://example.com/install.sh"));
    }

    // ── Integration tests (require Docker + network; gated by env var) ──────────

    @Test
    @EnabledIfEnvironmentVariable(named = "HUAWEI_CLI_TESTS", matches = "true")
    void run_hcloudVersion_succeeds() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = KooCLI.builder()
            .accessKeyId(Property.ofValue(System.getenv("HUAWEI_ACCESS_KEY")))
            .secretAccessKey(Property.ofValue(System.getenv("HUAWEI_SECRET_ACCESS_KEY")))
            .region(Property.ofValue(System.getenv().getOrDefault("HUAWEI_REGION", "eu-west-101")))
            .commands(Property.ofValue(List.of("hcloud version")))
            .build();

        var output = task.run(runContext);

        assertThat(output.getExitCode(), equalTo(0));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HUAWEI_CLI_TESTS", matches = "true")
    void run_captureOutput_writesToOutputFiles() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = KooCLI.builder()
            .accessKeyId(Property.ofValue(System.getenv("HUAWEI_ACCESS_KEY")))
            .secretAccessKey(Property.ofValue(System.getenv("HUAWEI_SECRET_ACCESS_KEY")))
            .region(Property.ofValue(System.getenv().getOrDefault("HUAWEI_REGION", "eu-west-101")))
            .commands(Property.ofValue(List.of("hcloud version > version.txt")))
            .outputFiles(Property.ofValue(List.of("version.txt")))
            .build();

        var output = task.run(runContext);

        assertThat(output.getExitCode(), equalTo(0));
        assertThat(output.getOutputFiles(), hasKey("version.txt"));
        assertThat(output.getOutputFiles().get("version.txt"), is(notNullValue()));
    }
}
