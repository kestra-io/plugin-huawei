package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.huawei.obs.tasks.Downloads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@EnabledIfEnvironmentVariable(named = "OBS_MINIO_TESTS", matches = "true")
class DownloadsTest extends AbstractMinioTest {

    @Test
    void downloads_actionNone_filesDownloadedAndObjectsRemain() throws Exception {
        var runId = IdUtils.create();
        var prefix = "downloads-test/" + runId + "/none/";
        seedObject(prefix + "a.txt", "content-a", "text/plain");
        seedObject(prefix + "b.txt", "content-b", "text/plain");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(Downloads.builder())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(prefix))
            .action(Property.ofValue(Downloads.Action.NONE))
            .build();

        var output = task.run(runContext);

        assertThat(output.getObjects().size(), equalTo(2));
        assertThat(output.getOutputFiles().size(), equalTo(2));

        // All objects must still exist in OBS
        assertThat(rawClient.doesObjectExist(testBucket, prefix + "a.txt"), is(true));
        assertThat(rawClient.doesObjectExist(testBucket, prefix + "b.txt"), is(true));

        // Internal storage URIs must be accessible
        for (var entry : output.getOutputFiles().entrySet()) {
            try (var in = runContext.storage().getFile(entry.getValue())) {
                assertThat(in, notNullValue());
            }
        }

        // ObsObject.uri must be populated
        output.getObjects().forEach(o -> assertThat(o.getUri(), notNullValue()));
    }

    @Test
    void downloads_actionDelete_objectsAreRemovedAfterDownload() throws Exception {
        var runId = IdUtils.create();
        var prefix = "downloads-test/" + runId + "/delete/";
        seedObject(prefix + "x.txt", "content-x", "text/plain");
        seedObject(prefix + "y.txt", "content-y", "text/plain");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(Downloads.builder())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(prefix))
            .action(Property.ofValue(Downloads.Action.DELETE))
            .build();

        var output = task.run(runContext);

        assertThat(output.getObjects().size(), equalTo(2));

        // Objects must be gone from OBS
        assertThat(rawClient.doesObjectExist(testBucket, prefix + "x.txt"), is(false));
        assertThat(rawClient.doesObjectExist(testBucket, prefix + "y.txt"), is(false));

        // Files must still be accessible in Kestra storage
        for (var uri : output.getOutputFiles().values()) {
            try (var in = runContext.storage().getFile(uri)) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8), not(emptyString()));
            }
        }
    }

    @Test
    void downloads_actionMove_objectsRelocatedToNewPrefix() throws Exception {
        var runId = IdUtils.create();
        var srcPrefix = "downloads-test/" + runId + "/move-src/";
        var dstPrefix = "downloads-test/" + runId + "/move-dst/";
        seedObject(srcPrefix + "file.txt", "content-move", "text/plain");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(Downloads.builder())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(srcPrefix))
            .action(Property.ofValue(Downloads.Action.MOVE))
            .moveTo(Downloads.MoveTo.builder()
                .keyPrefix(Property.ofValue(dstPrefix))
                .build())
            .build();

        var output = task.run(runContext);

        assertThat(output.getObjects().size(), equalTo(1));

        // Source must be gone
        assertThat(rawClient.doesObjectExist(testBucket, srcPrefix + "file.txt"), is(false));

        // Destination must exist
        var destKey = dstPrefix + srcPrefix + "file.txt";
        assertThat(rawClient.doesObjectExist(testBucket, destKey), is(true));
    }
}
