package io.kestra.plugin.huawei.dms.kafka;

import io.kestra.core.models.property.Property;
import io.kestra.core.utils.IdUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@EnabledIfEnvironmentVariable(named = "DMS_KAFKA_TESTS", matches = "true")
class TriggerTest extends AbstractDmsKafkaTest {

    @Test
    void trigger_noMessages_returnsEmpty() throws Exception {
        var topic = "kestra-trigger-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 10);
        var groupId = "kestra-trigger-group-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 6);
        var runContext = runContextFactory.of(Collections.emptyMap());

        var consume = consumeBuilder()
            .topic(Property.ofValue(topic))
            .groupId(Property.ofValue(groupId))
            .maxRecords(Property.ofValue(1))
            .maxDuration(Property.ofValue(Duration.ofSeconds(3)))
            .build();

        var output = consume.run(runContext);
        assertThat(output.getMessagesCount(), equalTo(0));
    }

    @Test
    void trigger_withMessages_returnsCount() throws Exception {
        var topic = "kestra-trigger-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 10);
        var groupId = "kestra-trigger-group-" + IdUtils.create().toLowerCase().replace("_", "-").substring(0, 6);
        var runContext = runContextFactory.of(Collections.emptyMap());

        // Produce a message first
        produceBuilder()
            .topic(Property.ofValue(topic))
            .from(List.of(Map.of("key", "k1", "value", "trigger-msg")))
            .build()
            .run(runContext);

        var consume = consumeBuilder()
            .topic(Property.ofValue(topic))
            .groupId(Property.ofValue(groupId))
            .maxRecords(Property.ofValue(1))
            .maxDuration(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        var output = consume.run(runContext);
        assertThat(output.getMessagesCount(), greaterThanOrEqualTo(1));
    }
}
