package io.kestra.plugin.huawei.dis;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.serializers.JacksonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Serialization type for the DIS record {@code data} payload.
 *
 * <p>DIS transports {@code data} as a base64-encoded string on the wire; this enum drives the
 * encode/decode logic applied on top of that, in {@link PutRecords} and {@link Consume}.
 */
public enum SerdeType {
    /** UTF-8 string pass-through. */
    STRING,
    /** JSON object serialized to/from a UTF-8 JSON string. Value must be a {@link Map}. */
    JSON,
    /** Raw bytes — no encoding; value must already be {@code byte[]}. */
    BINARY;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public byte[] serialize(Object value) throws Exception {
        if (value == null) {
            return null;
        }
        return switch (this) {
            case STRING -> value.toString().getBytes(StandardCharsets.UTF_8);
            case JSON -> JacksonMapper.ofJson().writeValueAsBytes(value);
            case BINARY -> (byte[]) value;
        };
    }

    public Object deserialize(byte[] bytes) throws Exception {
        if (bytes == null) {
            return null;
        }
        return switch (this) {
            case STRING -> new String(bytes, StandardCharsets.UTF_8);
            case JSON -> JacksonMapper.ofJson().readValue(bytes, MAP_TYPE);
            case BINARY -> bytes;
        };
    }
}
