package io.kestra.plugin.huawei.dis;

import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.core.storages.kv.KVValueAndMetadata;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists the per-partition "last delivered sequence number" watermark shared by {@link Trigger}
 * and {@link RealtimeTrigger} in the flow's namespace KV Store, so neither re-delivers a record
 * already seen on a previous poll (or a previous run of the realtime trigger).
 */
final class DisWatermark {

    private static final String DESCRIPTION =
        "DIS trigger watermark — per-partition last delivered sequence number, used to resume without re-delivering records.";

    private DisWatermark() {
    }

    /** Length-prefixes {@code flowId} so two flow/trigger id pairs can never collide on concatenation (e.g. `watch`/`high_cpu` vs `watch_high`/`cpu`). */
    static String key(TriggerContext triggerContext) {
        var flowId = triggerContext.getFlowId();
        return "dis_trigger_watermark_" + flowId.length() + "_" + flowId + "_" + triggerContext.getTriggerId();
    }

    static Map<String, String> read(KVStore kv, String key) throws Exception {
        return kv.getValue(key)
            .map(KVValue::value)
            .map(value -> {
                if (!(value instanceof Map<?, ?> map)) {
                    throw new IllegalStateException(
                        "DIS trigger watermark at KV key '" + key + "' has an unexpected shape (expected a map of " +
                        "partitionId to sequenceNumber) — delete the key to reset the watermark.");
                }
                var result = new LinkedHashMap<String, String>();
                map.forEach((k, v) -> result.put(String.valueOf(k), v != null ? String.valueOf(v) : null));
                return result;
            })
            .orElse(null);
    }

    static void write(KVStore kv, String key, Map<String, String> value) throws Exception {
        kv.put(key, new KVValueAndMetadata(new KVMetadata(DESCRIPTION, (Duration) null), value), true);
    }
}
