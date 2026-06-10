package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.huawei.obs.tasks.Delete;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@EnabledIfEnvironmentVariable(named = "OBS_MINIO_TESTS", matches = "true")
class DeleteTest extends AbstractMinioTest {

    @Test
    void delete_existingObject_isRemovedFromBucket() throws Exception {
        var key = "delete-test/" + IdUtils.create() + "/file.txt";
        seedObject(key, "to be deleted", "text/plain");

        assertThat(rawClient.doesObjectExist(testBucket, key), is(true));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(Delete.builder())
            .bucket(Property.ofValue(testBucket))
            .key(Property.ofValue(key))
            .build();

        var output = task.run(runContext);

        // On a non-versioned MinIO bucket, deleteMarker is false and the object is gone
        assertThat(output.isDeleteMarker(), is(false));
        assertThat(rawClient.doesObjectExist(testBucket, key), is(false));
    }

    @Test
    void delete_nonExistentKey_doesNotThrow() throws Exception {
        // On MinIO (and non-versioned OBS), deleting a key that doesn't exist is a no-op — the SDK
        // returns HTTP 204 with no error. deleteMarker is false.
        var key = "delete-test/" + IdUtils.create() + "/ghost.txt";

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(Delete.builder())
            .bucket(Property.ofValue(testBucket))
            .key(Property.ofValue(key))
            .build();

        var output = task.run(runContext);

        assertThat(output.isDeleteMarker(), is(false));
    }
}
