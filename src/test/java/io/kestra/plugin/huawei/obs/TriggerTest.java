package io.kestra.plugin.huawei.obs;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContextInitializer;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.huawei.obs.Trigger;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@EnabledIfEnvironmentVariable(named = "OBS_MINIO_TESTS", matches = "true")
class TriggerTest extends AbstractObsTest {

    @Inject
    RunContextInitializer runContextInitializer;

    @Test
    void evaluate_withMatchingObjects_returnsExecutionAndDeletesSources() throws Exception {
        var runId = IdUtils.create();
        var prefix = key(runId + "/");
        seedObject(prefix + "event1.json", "{}", "application/json");
        seedObject(prefix + "event2.json", "{}", "application/json");

        var trigger = applyObsConfig(Trigger.builder())
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(prefix))
            .action(Property.ofValue(Trigger.Action.DELETE))
            .build();

        var flow = buildFlow();
        var triggerContext = buildTriggerContext(trigger);
        var runContext = triggerRunContext(flow, trigger, triggerContext);
        var conditionContext = new ConditionContext(flow, null, runContext, Collections.emptyMap(), null);

        var result = trigger.evaluate(conditionContext, triggerContext);

        assertThat(result.isPresent(), is(true));
        var execution = result.get();
        assertThat(execution, notNullValue());
        assertThat(execution.getId(), notNullValue());

        // Objects must have been deleted from OBS (action=DELETE)
        assertThat(rawClient.doesObjectExist(testBucket, prefix + "event1.json"), is(false));
        assertThat(rawClient.doesObjectExist(testBucket, prefix + "event2.json"), is(false));
    }

    @Test
    void evaluate_noMatchingObjects_returnsEmpty() throws Exception {
        var trigger = applyObsConfig(Trigger.builder())
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(key(IdUtils.create() + "/nonexistent/")))
            .action(Property.ofValue(Trigger.Action.DELETE))
            .build();

        var flow = buildFlow();
        var triggerContext = buildTriggerContext(trigger);
        var runContext = triggerRunContext(flow, trigger, triggerContext);
        var conditionContext = new ConditionContext(flow, null, runContext, Collections.emptyMap(), null);

        var result = trigger.evaluate(conditionContext, triggerContext);

        assertThat(result.isPresent(), is(false));
    }

    @Test
    void evaluate_withMoveAction_objectsAreRelocated() throws Exception {
        var runId = IdUtils.create();
        var srcPrefix = key(runId + "/src/");
        var dstPrefix = key(runId + "/dst/");
        seedObject(srcPrefix + "data.csv", "col1,col2", "text/csv");

        var trigger = applyObsConfig(Trigger.builder())
            .id(IdUtils.create())
            .type(Trigger.class.getName())
            .bucket(Property.ofValue(testBucket))
            .prefix(Property.ofValue(srcPrefix))
            .action(Property.ofValue(Trigger.Action.MOVE))
            .moveTo(Trigger.MoveTo.builder()
                .keyPrefix(Property.ofValue(dstPrefix))
                .build())
            .build();

        var flow = buildFlow();
        var triggerContext = buildTriggerContext(trigger);
        var runContext = triggerRunContext(flow, trigger, triggerContext);
        var conditionContext = new ConditionContext(flow, null, runContext, Collections.emptyMap(), null);

        var result = trigger.evaluate(conditionContext, triggerContext);

        assertThat(result.isPresent(), is(true));

        // Source must be gone
        assertThat(rawClient.doesObjectExist(testBucket, srcPrefix + "data.csv"), is(false));

        // Destination must exist
        assertThat(rawClient.doesObjectExist(testBucket, dstPrefix + srcPrefix + "data.csv"), is(true));
    }

    private Flow buildFlow() {
        return Flow.builder()
            .id("trigger-test-flow")
            .namespace("company.team")
            .revision(1)
            .tasks(List.of())
            .build();
    }

    private TriggerContext buildTriggerContext(Trigger trigger) {
        return TriggerContext.builder()
            // "main" matches the default tenantId used by LocalStorage in test environments
            .tenantId("main")
            .namespace("company.team")
            .flowId("trigger-test-flow")
            .triggerId(trigger.getId())
            .date(ZonedDateTime.now())
            .build();
    }

    /**
     * Builds a RunContext that has a `triggerExecutionId` set — required by
     * {@link io.kestra.core.models.triggers.TriggerService#generateExecution}.
     */
    private DefaultRunContext triggerRunContext(Flow flow, Trigger trigger, TriggerContext triggerContext) {
        var base = (DefaultRunContext) runContextFactory.of(flow, trigger);
        return runContextInitializer.forScheduler(base, triggerContext, trigger);
    }
}
