package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.huawei.obs.tasks.DeleteList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "OBS_MINIO_TESTS", matches = "true")
class DeleteListTest extends AbstractMinioTest {

    @Test
    void deleteList_byPrefix_deletesOnlyMatchingObjects() throws Exception {
        var runId = IdUtils.create();
        var matchPrefix = "delete-list-test/" + runId + "/match/";
        var noMatchPrefix = "delete-list-test/" + runId + "/keep/";

        // Seed 3 objects under the prefix we'll delete and 2 that must survive
        seedObject(matchPrefix + "a.csv", "a", "text/csv");
        seedObject(matchPrefix + "b.csv", "b", "text/csv");
        seedObject(matchPrefix + "c.csv", "c", "text/csv");
        seedObject(noMatchPrefix + "x.txt", "x", "text/plain");
        seedObject(noMatchPrefix + "y.txt", "y", "text/plain");

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(DeleteList.builder())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(matchPrefix))
            .build();

        var output = task.run(runContext);

        assertThat(output.getCount(), equalTo(3L));
        assertThat(output.getSize(), greaterThan(0L));

        // Verify the prefix objects are gone
        assertThat(rawClient.doesObjectExist(testBucket, matchPrefix + "a.csv"), is(false));
        assertThat(rawClient.doesObjectExist(testBucket, matchPrefix + "b.csv"), is(false));
        assertThat(rawClient.doesObjectExist(testBucket, matchPrefix + "c.csv"), is(false));

        // Verify the other objects survived
        assertThat(rawClient.doesObjectExist(testBucket, noMatchPrefix + "x.txt"), is(true));
        assertThat(rawClient.doesObjectExist(testBucket, noMatchPrefix + "y.txt"), is(true));
    }

    @Test
    void deleteList_noMatch_errorOnEmptyTrue_throwsException() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(DeleteList.builder())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue("delete-list-test/" + IdUtils.create() + "/nonexistent/"))
            .errorOnEmpty(Property.ofValue(true))
            .build();

        assertThrows(IllegalStateException.class, () -> task.run(runContext));
    }

    @Test
    void deleteList_noMatch_errorOnEmptyFalse_returnsZeroCount() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(DeleteList.builder())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue("delete-list-test/" + IdUtils.create() + "/nonexistent/"))
            .build();

        var output = task.run(runContext);

        assertThat(output.getCount(), equalTo(0L));
        assertThat(output.getSize(), equalTo(0L));
    }
}
