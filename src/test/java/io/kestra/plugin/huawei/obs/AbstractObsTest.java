package io.kestra.plugin.huawei.obs;

import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;
import com.obs.services.exception.ObsException;
import com.obs.services.model.CreateBucketRequest;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.PutObjectRequest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Base class for OBS integration tests.
 *
 * <p>By default targets a local MinIO instance (started in CI via {@code docker-compose-ci.yml},
 * or locally with {@code docker compose -f docker-compose-ci.yml up -d}). Override via environment
 * variables for live QA against real Huawei Cloud OBS:
 *
 * <pre>
 *   OBS_TEST_ENDPOINT=https://obs.eu-west-101.myhuaweicloud.eu
 *   OBS_TEST_ACCESS_KEY=&lt;AK&gt;
 *   OBS_TEST_SECRET_KEY=&lt;SK&gt;
 *   OBS_TEST_AUTH_TYPE=OBS
 *   OBS_TEST_PATH_STYLE=false
 * </pre>
 *
 * <p>On tenants where the IAM user cannot create buckets (e.g. sovereign cloud with restricted
 * user policies), pre-create a bucket manually and point to it via:
 * <pre>
 *   OBS_TEST_BUCKET=my-pre-created-bucket
 * </pre>
 * When this variable is set the {@link #setUpBucket()} method skips creation and uses it directly.
 * The bucket must exist, be writable by the test credentials, and be exclusively used by this
 * test run (prefix isolation in each test provides logical separation).
 *
 * <p>The test gate ({@code OBS_MINIO_TESTS=true}) is unchanged and must always be set.
 */
@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractObsTest {

    static final String TEST_ENDPOINT = env("OBS_TEST_ENDPOINT", "http://localhost:9000");
    static final String TEST_AK       = env("OBS_TEST_ACCESS_KEY", "minioadmin");
    static final String TEST_SK       = env("OBS_TEST_SECRET_KEY", "minioadmin");
    static final AuthType TEST_AUTH_TYPE =
        AuthType.valueOf(env("OBS_TEST_AUTH_TYPE", "V2").toUpperCase(Locale.ROOT));
    static final boolean TEST_PATH_STYLE =
        Boolean.parseBoolean(env("OBS_TEST_PATH_STYLE", "true"));
    /** Optional: skip bucket creation and use this pre-existing bucket instead. */
    static final String TEST_BUCKET_OVERRIDE = System.getenv("OBS_TEST_BUCKET");

    /** Per-subclass bucket, initialised by {@link #setUpBucket()}. */
    String testBucket;

    /** Raw client used for seeding and verification, bypassing task code. */
    ObsClient rawClient;

    @Inject
    RunContextFactory runContextFactory;

    @BeforeAll
    void setUpBucket() {
        rawClient = buildRawClient();
        if (TEST_BUCKET_OVERRIDE != null) {
            // Use the pre-created bucket as-is; the IAM user does not need CreateBucket permission.
            testBucket = TEST_BUCKET_OVERRIDE;
            return;
        }
        // Include a short random suffix so bucket names are globally unique on real OBS.
        // Names are lowercase, 3-63 chars, no underscores — compliant with both OBS and MinIO.
        var suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        testBucket = "kestra-obs-" + getClass().getSimpleName().toLowerCase().replace("test", "").replace("_", "") + "-" + suffix;
        // Trim to 63 chars if a subclass name happens to produce a long string
        if (testBucket.length() > 63) {
            testBucket = testBucket.substring(0, 55) + "-" + suffix;
        }
        try {
            rawClient.createBucket(new CreateBucketRequest(testBucket));
        } catch (ObsException e) {
            var code = e.getErrorCode();
            if (!"BucketAlreadyOwnedByYou".equals(code) && !"BucketAlreadyExists".equals(code)) {
                throw e;
            }
        }
    }

    static ObsClient buildRawClient() {
        var cfg = new ObsConfiguration();
        cfg.setEndPoint(TEST_ENDPOINT);
        cfg.setPathStyle(TEST_PATH_STYLE);
        cfg.setAuthType(TEST_AUTH_TYPE.toSdkEnum());
        cfg.setHttpsOnly(TEST_ENDPOINT.startsWith("https://"));
        return new ObsClient(TEST_AK, TEST_SK, cfg);
    }

    /** Seeds a UTF-8 string object in the test bucket via the raw client. */
    void seedObject(String key, String content, String contentType) {
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        var meta = new ObjectMetadata();
        meta.setContentLength((long) bytes.length);
        if (contentType != null) meta.setContentType(contentType);
        var req = new PutObjectRequest(testBucket, key, new ByteArrayInputStream(bytes));
        req.setMetadata(meta);
        rawClient.putObject(req);
    }

    /**
     * Applies the configured connection settings to any OBS task builder that extends {@link AbstractObs}.
     *
     * <p>The raw-type cast is intentional: Lombok's fluent builder chains require the exact concrete builder
     * type, and the generic bound cannot express that constraint. Safe here because we only set inherited
     * {@link AbstractObs} properties that all concrete builders accept.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <B> B applyObsConfig(B builder) {
        var b = (io.kestra.plugin.huawei.obs.AbstractObs.AbstractObsBuilder) builder;
        b.accessKeyId(Property.ofValue(TEST_AK));
        b.secretAccessKey(Property.ofValue(TEST_SK));
        b.endpointOverride(Property.ofValue(TEST_ENDPOINT));
        b.pathStyleAccess(Property.ofValue(TEST_PATH_STYLE));
        b.authType(Property.ofValue(TEST_AUTH_TYPE));
        return (B) b;
    }

    /**
     * Applies the configured connection settings to a {@link io.kestra.plugin.huawei.obs.Trigger} builder.
     *
     * <p>Triggers extend {@code AbstractTrigger} rather than {@code AbstractObs}, so they need their own
     * helper that casts to the trigger's concrete builder type.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    io.kestra.plugin.huawei.obs.Trigger.TriggerBuilder<?, ?> applyObsConfig(
        io.kestra.plugin.huawei.obs.Trigger.TriggerBuilder<?, ?> builder
    ) {
        builder.accessKeyId(Property.ofValue(TEST_AK));
        builder.secretAccessKey(Property.ofValue(TEST_SK));
        builder.endpointOverride(Property.ofValue(TEST_ENDPOINT));
        builder.pathStyleAccess(Property.ofValue(TEST_PATH_STYLE));
        builder.authType(Property.ofValue(TEST_AUTH_TYPE));
        return builder;
    }

    private static String env(String name, String defaultValue) {
        var v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }
}
