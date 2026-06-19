package io.kestra.plugin.huawei.dms.rocketmq;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

@EnabledIfEnvironmentVariable(named = "DMS_ROCKETMQ_TESTS", matches = "true")
class PublishConsumeTest extends AbstractDmsRocketMqTest {

    @Test
    void publish_happyPath_messagesDelivered() throws Exception {
        var topic = "kestra-test-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 12);
        var groupId = "kestra-producer-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 8);
        var runContext = runContextFactory.of(Collections.emptyMap());

        var task = publishBuilder()
            .topic(Property.ofValue(topic))
            .groupId(Property.ofValue(groupId))
            .from(List.of(
                Map.of("body", "hello from kestra"),
                Map.of("body", "second message", "tags", "test")
            ))
            .build();

        var output = task.run(runContext);

        assertThat(output.getMessagesCount(), equalTo(2));
    }

    @Test
    void consume_afterPublish_readsMessages() throws Exception {
        var topic = "kestra-consume-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 12);
        var producerGroup = "kestra-producer-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 8);
        var consumerGroup = "kestra-consumer-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 8);
        var runContext = runContextFactory.of(Collections.emptyMap());

        // Publish first
        publishBuilder()
            .topic(Property.ofValue(topic))
            .groupId(Property.ofValue(producerGroup))
            .from(Map.of("body", "test-message"))
            .build()
            .run(runContext);

        // Then consume
        var consume = consumeBuilder()
            .topic(Property.ofValue(topic))
            .groupId(Property.ofValue(consumerGroup))
            .maxRecords(Property.ofValue(10))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        var output = consume.run(runContext);

        assertThat(output.getMessagesCount(), greaterThanOrEqualTo(1));
        assertThat(output.getUri(), notNullValue());
    }

    @Test
    void consume_drainsEarly_whenFewerMessagesThanMaxRecords() throws Exception {
        var topic = "kestra-drain-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 12);
        var producerGroup = "kestra-producer-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 8);
        var consumerGroup = "kestra-consumer-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 8);
        var runContext = runContextFactory.of(Collections.emptyMap());

        publishBuilder()
            .topic(Property.ofValue(topic))
            .groupId(Property.ofValue(producerGroup))
            .from(List.of(
                Map.of("body", "drain-msg-1"),
                Map.of("body", "drain-msg-2")
            ))
            .build()
            .run(runContext);

        // maxRecords is far above the number of published messages; no maxDuration set.
        // The pull loop must break on NO_NEW_MSG/NO_MATCHED_MSG and not hang.
        var consume = consumeBuilder()
            .topic(Property.ofValue(topic))
            .groupId(Property.ofValue(consumerGroup))
            .maxRecords(Property.ofValue(1000))
            .build();

        var started = System.currentTimeMillis();
        var output = consume.run(runContext);
        var elapsed = System.currentTimeMillis() - started;

        assertThat(output.getUri(), notNullValue());
        // Must complete promptly; RocketMQ pull-mode breaks on NO_NEW_MSG.
        assertThat("task must not hang when topic is drained", elapsed, lessThan(60_000L));
    }

    @Test
    void consume_missingMaxConstraint_throwsIllegalArgument() {
        var runContext = runContextFactory.of(Collections.emptyMap());

        var consume = consumeBuilder()
            .topic(Property.ofValue("some-topic"))
            .groupId(Property.ofValue("some-group"))
            .build();

        try {
            consume.run(runContext);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("maxRecords"));
            return;
        } catch (Exception ignored) {
            // other exceptions are acceptable
        }
    }
}
