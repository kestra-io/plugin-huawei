package io.kestra.plugin.huawei.dms.rocketmq;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Base class for DMS for RocketMQ integration tests.
 *
 * <p>Tests are gated by {@code DMS_ROCKETMQ_TESTS=true}. Start a RocketMQ instance and set:
 * <pre>
 *   DMS_ROCKETMQ_TESTS=true
 *   DMS_ROCKETMQ_NAME_SERVER=localhost:9876
 *   DMS_ROCKETMQ_AK=   (optional, leave empty for no-auth)
 *   DMS_ROCKETMQ_SK=   (optional)
 * </pre>
 *
 * <p>In CI, {@code .github/setup-unit.sh} starts a RocketMQ container via docker-compose-ci.yml
 * and exports the required env vars.
 */
@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DMS_ROCKETMQ_TESTS", matches = "true")
abstract class AbstractDmsRocketMqTest {

    static final String NAME_SERVER = env("DMS_ROCKETMQ_NAME_SERVER", "localhost:9876");
    static final String AK = env("DMS_ROCKETMQ_AK", "");
    static final String SK = env("DMS_ROCKETMQ_SK", "");

    @Inject
    RunContextFactory runContextFactory;

    protected Publish.PublishBuilder<?, ?> publishBuilder() {
        var b = Publish.builder()
            .nameServerAddr(Property.ofValue(NAME_SERVER));
        if (!AK.isBlank()) {
            b.accessKeyId(Property.ofValue(AK));
            b.secretAccessKey(Property.ofValue(SK));
        }
        return b;
    }

    protected Consume.ConsumeBuilder<?, ?> consumeBuilder() {
        var b = Consume.builder()
            .nameServerAddr(Property.ofValue(NAME_SERVER));
        if (!AK.isBlank()) {
            b.accessKeyId(Property.ofValue(AK));
            b.secretAccessKey(Property.ofValue(SK));
        }
        return b;
    }

    private static String env(String name, String defaultValue) {
        var v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }
}
