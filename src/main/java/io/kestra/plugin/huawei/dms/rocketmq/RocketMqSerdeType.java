package io.kestra.plugin.huawei.dms.rocketmq;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.serializers.JacksonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Body serialization type for DMS for RocketMQ messages.
 *
 * <p>RocketMQ message bodies are always {@code byte[]} on the wire; this enum drives
 * encode/decode in {@link Publish} and {@link Consume}.
 */
public enum RocketMqSerdeType {
    /** UTF-8 string pass-through. */
    STRING,
    /** JSON object serialized to/from a UTF-8 JSON string. Body must be a {@link Map}. */
    JSON;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public byte[] serialize(Object body) throws Exception {
        if (body == null) {
            return new byte[0];
        }
        return switch (this) {
            case STRING -> body.toString().getBytes(StandardCharsets.UTF_8);
            case JSON -> JacksonMapper.ofJson().writeValueAsBytes(body);
        };
    }

    public Object deserialize(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return switch (this) {
            case STRING -> new String(bytes, StandardCharsets.UTF_8);
            case JSON -> JacksonMapper.ofJson().readValue(bytes, MAP_TYPE);
        };
    }
}
