package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.huawei.obs.Upload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@EnabledIfEnvironmentVariable(named = "OBS_MINIO_TESTS", matches = "true")
class UploadTest extends AbstractObsTest {

    @Test
    void upload_happyPath_objectAppearsInBucket() throws Exception {
        var content = "hello from kestra obs upload test";
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        var key = "upload-test/" + IdUtils.create() + "/data.txt";
        trackForCleanup(key);

        // Stage file into Kestra internal storage
        var runContext = runContextFactory.of(Collections.emptyMap());
        URI storageUri;
        try (var in = new ByteArrayInputStream(bytes)) {
            storageUri = runContext.storage().putFile(in, "data.txt");
        }

        var task = applyObsConfig(Upload.builder())
            .bucket(Property.ofValue(testBucket))
            .from(Property.ofValue(storageUri.toString()))
            .key(Property.ofValue(key))
            .contentType(Property.ofValue("text/plain"))
            .metadata(Property.ofValue(Map.of("author", "kestra-test")))
            .build();

        var output = task.run(runContext);

        assertThat(output.getBucket(), equalTo(testBucket));
        assertThat(output.getKey(), equalTo(key));
        assertThat(output.getETag(), notNullValue());

        // Verify via raw client
        var sdkObj = rawClient.getObject(testBucket, key);
        var actual = new String(sdkObj.getObjectContent().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(actual, equalTo(content));
        assertThat(sdkObj.getMetadata().getContentType(), equalTo("text/plain"));
    }

    @Test
    void upload_noContentType_succeeds() throws Exception {
        var content = "binary-like content";
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        var key = "upload-test/" + IdUtils.create() + "/noct.bin";
        trackForCleanup(key);

        var runContext = runContextFactory.of(Collections.emptyMap());
        URI storageUri;
        try (var in = new ByteArrayInputStream(bytes)) {
            storageUri = runContext.storage().putFile(in, "noct.bin");
        }

        var task = applyObsConfig(Upload.builder())
            .bucket(Property.ofValue(testBucket))
            .from(Property.ofValue(storageUri.toString()))
            .key(Property.ofValue(key))
            .build();

        var output = task.run(runContext);
        assertThat(output.getETag(), notNullValue());

        // Verify content round-trips
        var sdkObj = rawClient.getObject(testBucket, key);
        var actual = new String(sdkObj.getObjectContent().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(actual, equalTo(content));
    }
}
