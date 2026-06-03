package io.kestra.plugin.huawei.obs;

import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;
import com.obs.services.exception.ObsException;
import com.obs.services.model.AuthTypeEnum;
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

/**
 * Base class for OBS integration tests that require a running MinIO instance.
 *
 * <p>MinIO is configured at {@code http://localhost:9000} with the default {@code minioadmin/minioadmin}
 * credentials. Start it before running the tests:
 * <pre>
 *   docker compose up -d minio
 * </pre>
 *
 * <p>Each subclass gets a unique bucket created once in {@code @BeforeAll}. The raw {@link ObsClient}
 * ({@link #rawClient}) is available for seeding objects and verifying state without going through Kestra tasks.
 */
@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractMinioTest {

    static final String MINIO_ENDPOINT = "http://localhost:9000";
    static final String MINIO_AK = "minioadmin";
    static final String MINIO_SK = "minioadmin";

    /** Per-subclass bucket, initialised by {@link #setUpBucket()}. */
    String testBucket;

    /** Raw client used for seeding and verification, bypassing task code. */
    ObsClient rawClient;

    @Inject
    RunContextFactory runContextFactory;

    @BeforeAll
    void setUpBucket() {
        rawClient = buildRawClient();
        testBucket = "kestra-obs-test-" + getClass().getSimpleName().toLowerCase();
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
        cfg.setEndPoint(MINIO_ENDPOINT);
        cfg.setPathStyle(true);
        cfg.setAuthType(AuthTypeEnum.V2);
        cfg.setHttpsOnly(false);
        return new ObsClient(MINIO_AK, MINIO_SK, cfg);
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
     * Applies MinIO connection config to any OBS task builder that extends {@link AbstractObs}.
     *
     * <p>The raw-type cast is intentional: Lombok's fluent builder chains require the exact concrete builder
     * type, and the generic bound cannot express that constraint. Safe here because we only set inherited
     * {@link AbstractObs} properties that all concrete builders accept.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    <B> B applyMinioConfig(B builder) {
        var b = (io.kestra.plugin.huawei.obs.AbstractObs.AbstractObsBuilder) builder;
        b.accessKeyId(Property.ofValue(MINIO_AK));
        b.secretAccessKey(Property.ofValue(MINIO_SK));
        b.endpointOverride(Property.ofValue(MINIO_ENDPOINT));
        b.pathStyleAccess(Property.ofValue(true));
        b.authType(Property.ofValue(AuthType.V2));
        return (B) b;
    }

    /**
     * Applies MinIO connection config to a {@link io.kestra.plugin.huawei.obs.tasks.Trigger} builder.
     *
     * <p>Triggers extend {@code AbstractTrigger} rather than {@code AbstractObs}, so they need their own
     * helper that casts to the trigger's concrete builder type.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    io.kestra.plugin.huawei.obs.tasks.Trigger.TriggerBuilder<?, ?> applyMinioConfig(
        io.kestra.plugin.huawei.obs.tasks.Trigger.TriggerBuilder<?, ?> builder
    ) {
        builder.accessKeyId(Property.ofValue(MINIO_AK));
        builder.secretAccessKey(Property.ofValue(MINIO_SK));
        builder.endpointOverride(Property.ofValue(MINIO_ENDPOINT));
        builder.pathStyleAccess(Property.ofValue(true));
        builder.authType(Property.ofValue(AuthType.V2));
        return builder;
    }
}
