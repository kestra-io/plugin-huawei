package io.kestra.plugin.huawei.dms.rocketmq;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumeTest extends AbstractDmsRocketMqTest {

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void stopAndKillDoNotThrowWhenNotRunning() {
        var task = Consume.builder()
            .topic(Property.ofValue("topic"))
            .groupId(Property.ofValue("group"))
            .maxRecords(Property.ofValue(10))
            .build();

        assertDoesNotThrow(task::stop);
        assertDoesNotThrow(task::kill);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DMS_ROCKETMQ_TESTS", matches = "true")
    void consume_stoppedWhilePulling_exitsEarly() throws Exception {
        var topic = "kestra-consume-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 12);
        var producerGroup = "kestra-producer-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 8);
        var consumerGroup = "kestra-consumer-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 8);
        var runContext = runContextFactory.of(Collections.emptyMap());

        publishBuilder()
            .topic(Property.ofValue(topic))
            .groupId(Property.ofValue(producerGroup))
            .from(List.of(
                Map.of("body", "msg1"),
                Map.of("body", "msg2")
            ))
            .build()
            .run(runContext);

        var consume = consumeBuilder()
            .topic(Property.ofValue(topic))
            .groupId(Property.ofValue(consumerGroup))
            .maxRecords(Property.ofValue(1000))
            .maxDuration(Property.ofValue(Duration.ofSeconds(30)))
            .build();

        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(consume::stop, 500, TimeUnit.MILLISECONDS);

        var started = System.currentTimeMillis();
        var output = consume.run(runContext);
        var elapsed = System.currentTimeMillis() - started;
        executor.shutdown();

        assertThat(output.getUri(), notNullValue());
        assertThat("task should exit promptly on stop()", elapsed, lessThan(5000L));
    }
}
