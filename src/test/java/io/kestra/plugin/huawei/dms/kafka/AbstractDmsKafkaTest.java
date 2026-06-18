package io.kestra.plugin.huawei.dms.kafka;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Base class for DMS for Kafka integration tests.
 *
 * <p>Tests are gated by {@code DMS_KAFKA_TESTS=true}. Start a Kafka container locally and set:
 * <pre>
 *   DMS_KAFKA_TESTS=true
 *   DMS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
 * </pre>
 *
 * <p>In CI, {@code .github/setup-unit.sh} starts a Kafka container via docker-compose-ci.yml and
 * exports the required env vars.
 */
@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DMS_KAFKA_TESTS", matches = "true")
abstract class AbstractDmsKafkaTest {

    static final String BOOTSTRAP_SERVERS = env("DMS_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");

    @Inject
    RunContextFactory runContextFactory;

    protected Produce.ProduceBuilder<?, ?> produceBuilder() {
        return Produce.builder()
            .bootstrapServers(Property.ofValue(BOOTSTRAP_SERVERS))
            .saslMechanism(Property.ofValue(SaslMechanism.NONE));
    }

    protected Consume.ConsumeBuilder<?, ?> consumeBuilder() {
        return Consume.builder()
            .bootstrapServers(Property.ofValue(BOOTSTRAP_SERVERS))
            .saslMechanism(Property.ofValue(SaslMechanism.NONE));
    }

    private static String env(String name, String defaultValue) {
        var v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }
}
