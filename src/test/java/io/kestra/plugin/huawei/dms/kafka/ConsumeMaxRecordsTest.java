package io.kestra.plugin.huawei.dms.kafka;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Broker-free: one poll() returning more than maxRecords must neither write nor commit the surplus.
@KestraTest
class ConsumeMaxRecordsTest {

    private static final String TOPIC = "max-records-topic";

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void consume_singleBatchLargerThanMaxRecords_writesAndCommitsOnlyMaxRecords() throws Exception {
        var partition = new TopicPartition(TOPIC, 0);
        var mock = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            // No-op so committed() stays readable after the task closes the consumer.
            @Override
            public void close() {
            }
        };
        mock.schedulePollTask(() -> {
            mock.rebalance(List.of(partition));
            mock.updateBeginningOffsets(Map.of(partition, 0L));
            for (long offset = 0; offset < 4; offset++) {
                mock.addRecord(new ConsumerRecord<>(
                    TOPIC, 0, offset,
                    ("k" + offset).getBytes(StandardCharsets.UTF_8),
                    ("v" + offset).getBytes(StandardCharsets.UTF_8)
                ));
            }
        });

        var task = MockedConsume.builder()
            .mock(mock)
            .topic(Property.ofValue(TOPIC))
            .groupId(Property.ofValue("group"))
            .maxRecords(Property.ofValue(2))
            .build();

        var output = task.run(runContextFactory.of(Collections.emptyMap()));

        assertThat(output.getMessagesCount(), equalTo(2));
        assertThat(mock.committed(Set.of(partition)).get(partition).offset(), equalTo(2L));
    }

    @Test
    void consume_maxRecordsBelowOne_isRejectedBeforePolling() {
        var mock = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST);
        var task = MockedConsume.builder()
            .mock(mock)
            .topic(Property.ofValue(TOPIC))
            .groupId(Property.ofValue("group"))
            .maxRecords(Property.ofValue(0))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Collections.emptyMap())));

        assertThat(ex.getMessage(), containsString("'maxRecords' must be at least 1"));
        assertThat(mock.subscription().isEmpty(), equalTo(true));
    }

    @SuperBuilder
    @NoArgsConstructor
    @ToString
    @EqualsAndHashCode(callSuper = true)
    // Must stay public: a non-public task class breaks Kestra's plugin scan and the whole test context.
    public static class MockedConsume extends Consume {
        private MockConsumer<byte[], byte[]> mock;

        @Override
        protected Consumer<byte[], byte[]> consumer(RunContext runContext, String groupId) {
            return mock;
        }
    }
}
