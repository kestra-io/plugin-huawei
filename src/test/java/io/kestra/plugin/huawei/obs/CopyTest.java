package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.huawei.obs.tasks.Copy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "OBS_MINIO_TESTS", matches = "true")
class CopyTest extends AbstractObsTest {

    @Test
    void copy_happyPath_destinationHasSameContent() throws Exception {
        var content = "copy test content";
        var srcKey = "copy-test/" + IdUtils.create() + "/src.txt";
        var dstKey = "copy-test/" + IdUtils.create() + "/dst.txt";
        seedObject(srcKey, content, "text/plain");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyObsConfig(Copy.builder())
            .from(Copy.CopyObjectFrom.builder()
                .bucket(Property.ofValue(testBucket))
                .key(Property.ofValue(srcKey))
                .build())
            .to(Copy.CopyObjectTo.builder()
                .bucket(Property.ofValue(testBucket))
                .key(Property.ofValue(dstKey))
                .build())
            .build();

        var output = task.run(runContext);

        assertThat(output.getBucket(), equalTo(testBucket));
        assertThat(output.getKey(), equalTo(dstKey));
        assertThat(output.getETag(), notNullValue());

        // Source must still exist
        var srcObj = rawClient.getObject(testBucket, srcKey);
        assertThat(new String(srcObj.getObjectContent().readAllBytes(), StandardCharsets.UTF_8), equalTo(content));

        // Destination must have the same content
        var dstObj = rawClient.getObject(testBucket, dstKey);
        assertThat(new String(dstObj.getObjectContent().readAllBytes(), StandardCharsets.UTF_8), equalTo(content));
    }

    @Test
    void copy_withDeleteTrue_sourceIsRemovedAfterCopy() throws Exception {
        var content = "move me";
        var srcKey = "copy-test/" + IdUtils.create() + "/to-move.txt";
        var dstKey = "copy-test/" + IdUtils.create() + "/moved.txt";
        seedObject(srcKey, content, "text/plain");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyObsConfig(Copy.builder())
            .from(Copy.CopyObjectFrom.builder()
                .bucket(Property.ofValue(testBucket))
                .key(Property.ofValue(srcKey))
                .build())
            .to(Copy.CopyObjectTo.builder()
                .bucket(Property.ofValue(testBucket))
                .key(Property.ofValue(dstKey))
                .build())
            .delete(Property.ofValue(true))
            .build();

        var output = task.run(runContext);

        assertThat(output.getBucket(), equalTo(testBucket));
        assertThat(output.getKey(), equalTo(dstKey));

        // Source must be gone
        assertThat(rawClient.doesObjectExist(testBucket, srcKey), is(false));

        // Destination must have the content
        var dstObj = rawClient.getObject(testBucket, dstKey);
        assertThat(new String(dstObj.getObjectContent().readAllBytes(), StandardCharsets.UTF_8), equalTo(content));
    }
}
