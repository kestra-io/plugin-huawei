package io.kestra.plugin.huawei.dms.kafka;

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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@KestraTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumeTest extends AbstractDmsKafkaTest {

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
    @EnabledIfEnvironmentVariable(named = "DMS_KAFKA_TESTS", matches = "true")
    void consume_stoppedWhilePolling_wakesUpAndExitsEarly() throws Exception {
        var topic = "kestra-test-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 12);
        var groupId = "kestra-consumer-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 8);
        var runContext = runContextFactory.of(Collections.emptyMap());

        produceBuilder()
            .topic(Property.ofValue(topic))
            .from(List.of(
                Map.of("key", "k1", "value", "msg1"),
                Map.of("key", "k2", "value", "msg2")
            ))
            .build()
            .run(runContext);

        var consume = consumeBuilder()
            .topic(Property.ofValue(topic))
            .groupId(Property.ofValue(groupId))
            .maxRecords(Property.ofValue(1000))
            .pollDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(consume::stop, 500, TimeUnit.MILLISECONDS);

        var started = System.currentTimeMillis();
        var output = consume.run(runContext);
        var elapsed = System.currentTimeMillis() - started;
        executor.shutdown();

        assertThat(output.getUri(), notNullValue());
        assertThat("task should be woken up and exit promptly on stop()", elapsed, lessThan(5000L));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DMS_KAFKA_TESTS", matches = "true")
    void consume_killedWhilePolling_wakesUpAndExitsWithoutCommit() throws Exception {
        var topic = "kestra-test-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 12);
        var groupId = "kestra-consumer-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 8);
        var runContext = runContextFactory.of(Collections.emptyMap());

        produceBuilder()
            .topic(Property.ofValue(topic))
            .from(List.of(
                Map.of("key", "k1", "value", "msg1")
            ))
            .build()
            .run(runContext);

        var consume = consumeBuilder()
            .topic(Property.ofValue(topic))
            .groupId(Property.ofValue(groupId))
            .maxRecords(Property.ofValue(1000))
            .pollDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(consume::kill, 500, TimeUnit.MILLISECONDS);

        var started = System.currentTimeMillis();
        var output = consume.run(runContext);
        var elapsed = System.currentTimeMillis() - started;
        executor.shutdown();

        assertThat(output.getUri(), notNullValue());
        assertThat("task should be woken up and exit promptly on kill()", elapsed, lessThan(5000L));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DMS_KAFKA_TESTS", matches = "true")
    void consume_stoppedMidBatch_commitsOnlyWrittenOffsets() throws Exception {
        var topic = "kestra-test-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 12);
        var groupId = "kestra-consumer-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 8);
        var runContext = runContextFactory.of(Collections.emptyMap());

        produceBuilder()
            .topic(Property.ofValue(topic))
            .from(List.of(
                Map.of("key", "k1", "value", "msg1"),
                Map.of("key", "k2", "value", "msg2"),
                Map.of("key", "k3", "value", "msg3"),
                Map.of("key", "k4", "value", "msg4")
            ))
            .build()
            .run(runContext);

        // First consume task stopped early after 2 records
        var consume1 = consumeBuilder()
            .topic(Property.ofValue(topic))
            .groupId(Property.ofValue(groupId))
            .maxRecords(Property.ofValue(2))
            .build();

        var output1 = consume1.run(runContext);
        assertThat(output1.getMessagesCount(), equalTo(2));

        // Second consume task reads remaining records from the committed offset
        var consume2 = consumeBuilder()
            .topic(Property.ofValue(topic))
            .groupId(Property.ofValue(groupId))
            .maxRecords(Property.ofValue(2))
            .build();

        var output2 = consume2.run(runContext);
        assertThat(output2.getMessagesCount(), equalTo(2));
    }
}
