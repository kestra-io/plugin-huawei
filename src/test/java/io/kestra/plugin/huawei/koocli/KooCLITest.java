package io.kestra.plugin.huawei.koocli;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class KooCLITest {

    private static final String FAKE_AK = "FAKEACCESSKEY0001";
    private static final String FAKE_SK = "fakeSecretKey0001fakeSecretKey001";

    @Inject
    RunContextFactory runContextFactory;

    // ── Unit tests (no Docker, no network) ──────────────────────────────────────

    @Test
    void defaults_containerImageIsUbuntu() {
        var task = KooCLI.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .commands(Property.ofValue(List.of("hcloud version")))
            .build();

        assertThat(task.getContainerImage(), equalTo("ubuntu:22.04"));
    }

    @Test
    void customContainerImage_isPreserved() {
        var task = KooCLI.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .commands(Property.ofValue(List.of("hcloud version")))
            .containerImage("my-registry/hcloud-ready:1.0")
            .build();

        assertThat(task.getContainerImage(), equalTo("my-registry/hcloud-ready:1.0"));
    }

    @Test
    void customEnv_isStoredOnTask() {
        var task = KooCLI.builder()
            .accessKeyId(Property.ofValue(FAKE_AK))
            .secretAccessKey(Property.ofValue(FAKE_SK))
            .commands(Property.ofValue(List.of("hcloud version")))
            .env(Map.of("MY_VAR", "hello"))
            .build();

        assertThat(task.getEnv(), hasEntry("MY_VAR", "hello"));
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
