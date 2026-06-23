package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.huawei.obs.tasks.Download;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@EnabledIfEnvironmentVariable(named = "OBS_MINIO_TESTS", matches = "true")
class DownloadTest extends AbstractObsTest {

    @Test
    void download_happyPath_contentRoundTrips() throws Exception {
        var content = "content seeded for download test";
        var key = "download-test/" + IdUtils.create() + "/file.txt";
        seedObject(key, content, "text/plain");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyObsConfig(Download.builder())
            .bucket(Property.ofValue(testBucket))
            .key(Property.ofValue(key))
            .build();

        var output = task.run(runContext);

        assertThat(output.getUri(), notNullValue());
        assertThat(output.getContentLength(), equalTo((long) content.getBytes(StandardCharsets.UTF_8).length));
        assertThat(output.getContentType(), equalTo("text/plain"));

        // Verify content via Kestra storage
        try (var in = runContext.storage().getFile(output.getUri())) {
            var actual = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(actual, equalTo(content));
        }
    }

    @Test
    void download_storageUriIsAccessible() throws Exception {
        var content = "download uri accessibility test";
        var key = "download-test/" + IdUtils.create() + "/uri-check.txt";
        seedObject(key, content, "application/octet-stream");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyObsConfig(Download.builder())
            .bucket(Property.ofValue(testBucket))
            .key(Property.ofValue(key))
            .build();

        var output = task.run(runContext);

        assertThat(output.getUri().getScheme(), equalTo("kestra"));
        assertThat(output.getContentType(), equalTo("application/octet-stream"));
    }
}
