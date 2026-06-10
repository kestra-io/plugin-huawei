package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.huawei.obs.tasks.ObsList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "OBS_MINIO_TESTS", matches = "true")
class ObsListTest extends AbstractMinioTest {

    private String prefix;

    @BeforeAll
    void seedObjects() {
        // Use a unique prefix per test run so concurrent runs don't interfere
        prefix = "list-test/" + IdUtils.create() + "/";
        seedObject(prefix + "a/file1.csv", "csv1", "text/csv");
        seedObject(prefix + "a/file2.csv", "csv2", "text/csv");
        seedObject(prefix + "b/data.json", "{}", "application/json");
        seedObject(prefix + "b/other.txt", "txt", "text/plain");
        seedObject(prefix + "c/archive.tar.gz", "gz", "application/gzip");
    }

    @Test
    void list_noFilter_returnsAllObjects() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(ObsList.builder())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(prefix))
            .build();

        var output = task.run(runContext);
        assertThat(output.getObjects().size(), equalTo(5));
    }

    @Test
    void list_prefixFilter_returnsSubset() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(ObsList.builder())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(prefix + "a/"))
            .build();

        var output = task.run(runContext);
        assertThat(output.getObjects().size(), equalTo(2));
        output.getObjects().forEach(o -> assertThat(o.getKey(), startsWith(prefix + "a/")));
    }

    @Test
    void list_regexpFilter_returnsMatchingOnly() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(ObsList.builder())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(prefix))
            .regexp(Property.ofValue(".*\\.csv"))
            .build();

        var output = task.run(runContext);
        assertThat(output.getObjects().size(), equalTo(2));
        output.getObjects().forEach(o -> assertThat(o.getKey(), endsWith(".csv")));
    }

    @Test
    void list_maxKeys_pagesCorrectly() throws Exception {
        // maxKeys=2 forces multiple pages; we should still get all 5 objects
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(ObsList.builder())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(prefix))
            .maxKeys(Property.ofValue(2))
            .build();

        var output = task.run(runContext);
        assertThat(output.getObjects().size(), equalTo(5));
    }

    @Test
    void list_maxResultsExceeded_failsFast() {
        // 5 objects match but maxResults=3 — the task must fail fast rather than materialise them all.
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(ObsList.builder())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(prefix))
            .maxResults(Property.ofValue(3))
            .build();

        var thrown = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(thrown.getMessage(), containsString("maxResults=3"));
    }

    @Test
    void list_maxResultsNotExceeded_returnsAll() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(ObsList.builder())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(prefix))
            .maxResults(Property.ofValue(10))
            .build();

        var output = task.run(runContext);
        assertThat(output.getObjects().size(), equalTo(5));
    }

    @Test
    void list_objectsHaveExpectedFields() throws Exception {
        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(ObsList.builder())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(prefix + "a/file1.csv"))
            .build();

        var output = task.run(runContext);
        assertThat(output.getObjects().size(), equalTo(1));
        var obj = output.getObjects().getFirst();
        assertThat(obj.getKey(), equalTo(prefix + "a/file1.csv"));
        assertThat(obj.getSize(), greaterThan(0L));
        assertThat(obj.getLastModified(), notNullValue());
    }
}
