package io.kestra.plugin.huawei.obs;

import com.obs.services.exception.ObsException;
import com.obs.services.model.CreateBucketRequest;
import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.huawei.obs.tasks.DeleteBucket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfEnvironmentVariable(named = "OBS_MINIO_TESTS", matches = "true")
class DeleteBucketTest extends AbstractMinioTest {

    /**
     * Bucket deletion tests require DeleteBucket permission. When {@code OBS_TEST_BUCKET} is set
     * (pre-created bucket mode), these tests are skipped because the test bucket must not be deleted.
     */
    private static void assumeBucketDeletionAllowed() {
        assumeTrue(
            TEST_BUCKET_OVERRIDE == null,
            "Skipped: OBS_TEST_BUCKET is set, cannot delete a shared pre-created bucket"
        );
    }

    private String uniqueBucketName() {
        return "kestra-obs-db-" + IdUtils.create().substring(0, 8).toLowerCase();
    }

    @Test
    void deleteBucket_existingEmptyBucket_succeeds() throws Exception {
        assumeBucketDeletionAllowed();
        var bucketName = uniqueBucketName();
        rawClient.createBucket(new CreateBucketRequest(bucketName));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(DeleteBucket.builder())
            .bucket(Property.ofValue(bucketName))
            .build();

        var output = task.run(runContext);

        assertThat(output.getBucket(), equalTo(bucketName));
        assertThat(output.isDeleted(), is(true));
    }

    @Test
    void deleteBucket_missingBucket_idempotentSuccess() throws Exception {
        assumeBucketDeletionAllowed();
        var bucketName = uniqueBucketName();
        // Intentionally do not create the bucket.
        // NOTE: MinIO surfaces absence via "NoSuchBucket" error code on the deleteBucket call
        // itself. Real OBS instead fires a HEAD <bucket>?apiversion region-probe first and returns
        // HTTP 404 (null error code) for a non-existent bucket — that path cannot be exercised
        // against MinIO. Both are handled by the broadened catch condition in DeleteBucket.

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(DeleteBucket.builder())
            .bucket(Property.ofValue(bucketName))
            .errorOnMissing(Property.ofValue(false))
            .build();

        var output = task.run(runContext);

        assertThat(output.getBucket(), equalTo(bucketName));
        assertThat(output.isDeleted(), is(false));
    }

    @Test
    void deleteBucket_missingBucket_errorOnMissing_throws() throws Exception {
        assumeBucketDeletionAllowed();
        var bucketName = uniqueBucketName();
        // Intentionally do not create the bucket

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyMinioConfig(DeleteBucket.builder())
            .bucket(Property.ofValue(bucketName))
            .errorOnMissing(Property.ofValue(true))
            .build();

        assertThrows(ObsException.class, () -> task.run(runContext));
    }
}
