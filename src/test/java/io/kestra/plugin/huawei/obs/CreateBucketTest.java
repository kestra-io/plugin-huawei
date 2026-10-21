package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.huawei.obs.CreateBucket;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfObsTests
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
        // Use a short unique suffix — bucket names must be ≤63 chars and DNS-compliant
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

        // Must not throw (idempotent success).
        // NOTE: MinIO and real Huawei Cloud OBS signal an existing bucket via a 409
        // "BucketAlreadyOwnedByYou" response, which CreateBucket catches to report created=false.
        // RustFS (and standard AWS S3) instead implements idempotent PUT bucket semantics returning
        // HTTP 200 OK, which the SDK treats as success (reporting created=true). Both satisfy idempotency.
        assertThat(output.getBucket(), equalTo(bucketName));
        assertThat(rawClient.headBucket(bucketName), is(true));

        // Clean up
        rawClient.deleteBucket(bucketName);
    }
}
