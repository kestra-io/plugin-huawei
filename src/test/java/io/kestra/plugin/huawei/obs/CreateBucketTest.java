package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.huawei.obs.CreateBucket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfEnvironmentVariable(named = "OBS_MINIO_TESTS", matches = "true")
class CreateBucketTest extends AbstractObsTest {

    /**
     * Bucket creation tests require CreateBucket permission. When {@code OBS_TEST_BUCKET} is set
     * (pre-created bucket mode, used for tenants with restricted IAM policies), these tests are
     * skipped because the credential does not have bucket creation rights.
     */
    private static void assumeBucketCreationAllowed() {
        assumeTrue(
            TEST_BUCKET_OVERRIDE == null,
            "Skipped: OBS_TEST_BUCKET is set, indicating the IAM user cannot create buckets"
        );
    }

    @Test
    void createBucket_newBucket_succeeds() throws Exception {
        assumeBucketCreationAllowed();
        // Use a short unique suffix — MinIO bucket names must be ≤63 chars and DNS-compliant
        var bucketName = "kestra-obs-cb-" + IdUtils.create().substring(0, 8).toLowerCase();

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyObsConfig(CreateBucket.builder())
            .bucket(Property.ofValue(bucketName))
            .build();

        var output = task.run(runContext);

        assertThat(output.getBucket(), equalTo(bucketName));
        assertThat(output.isCreated(), is(true));
        assertThat(rawClient.headBucket(bucketName), is(true));

        // Clean up
        rawClient.deleteBucket(bucketName);
    }

    @Test
    void createBucket_existingBucket_idempotentSuccess() throws Exception {
        assumeBucketCreationAllowed();
        var bucketName = "kestra-obs-cb-idem-" + IdUtils.create().substring(0, 6).toLowerCase();

        // Pre-create via raw client
        rawClient.createBucket(new com.obs.services.model.CreateBucketRequest(bucketName));

        var runContext = runContextFactory.of(Collections.emptyMap());
        var task = applyObsConfig(CreateBucket.builder())
            .bucket(Property.ofValue(bucketName))
            .build();

        var output = task.run(runContext);

        // Must not throw; created=false since it already existed
        assertThat(output.getBucket(), equalTo(bucketName));
        assertThat(output.isCreated(), is(false));

        // Clean up
        rawClient.deleteBucket(bucketName);
    }
}
