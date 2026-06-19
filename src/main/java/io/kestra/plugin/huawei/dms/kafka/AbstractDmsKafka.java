package io.kestra.plugin.huawei.dms.kafka;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import java.util.Properties;

/**
 * Base class for all DMS for Kafka tasks and triggers.
 *
 * <p>Builds the {@link Properties} that drive the Kafka client from the DMS connection properties.
 * Credentials are never logged — the JAAS config string is built from rendered secrets and only
 * held in the transient {@link Properties} object, never stored in a field.
 */
@SuperBuilder
@ToString(exclude = {"username", "password"})
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractDmsKafka extends AbstractConnection implements DmsKafkaConnectionInterface {

    @Schema(
        title = "Kafka bootstrap servers.",
        description = "Comma-separated list of `host:port` pairs that the Kafka client uses for the initial " +
            "cluster connection. For DMS for Kafka, copy this value from the instance detail page in the console."
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> bootstrapServers;

    @Schema(
        title = "SASL mechanism used for authentication.",
        description = """
            `PLAIN` — username/password (default, used by most DMS for Kafka instances).
            `SCRAM_SHA_512` — stronger challenge-response, supported on newer instances.
            `NONE` — no SASL; for VPC-internal clusters with no auth enabled.
            """
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<SaslMechanism> saslMechanism = Property.ofValue(SaslMechanism.PLAIN);

    @Schema(
        title = "SASL username.",
        description = "Required when `saslMechanism` is `PLAIN` or `SCRAM_SHA_512`."
    )
    @PluginProperty(group = "connection", secret = true)
    protected Property<String> username;

    @Schema(
        title = "SASL password.",
        description = "Required when `saslMechanism` is `PLAIN` or `SCRAM_SHA_512`. " +
            "**Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @PluginProperty(group = "connection", secret = true)
    protected Property<String> password;

    @Schema(
        title = "Enable TLS for the Kafka connection.",
        description = "Set to `true` to use `SASL_SSL` instead of `SASL_PLAINTEXT`. " +
            "DMS for Kafka instances accessed over the public internet require TLS."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<Boolean> sslEnabled = Property.ofValue(false);

    @Schema(
        title = "Key serializer/deserializer type.",
        description = "`STRING` (default), `JSON`, or `BINARY`."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    protected Property<SerdeType> keySerdeType = Property.ofValue(SerdeType.STRING);

    @Schema(
        title = "Value serializer/deserializer type.",
        description = "`STRING` (default), `JSON`, or `BINARY`."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    protected Property<SerdeType> valueSerdeType = Property.ofValue(SerdeType.STRING);

    /**
     * Builds a Kafka {@link Properties} object from the rendered task properties.
     *
     * <p>The JAAS config is constructed inline from rendered credentials and not stored in any field.
     */
    protected Properties kafkaProperties(RunContext runContext) throws IllegalVariableEvaluationException {
        var props = new Properties();

        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG,
            runContext.render(bootstrapServers).as(String.class).orElseThrow());

        var mechanism = runContext.render(saslMechanism).as(SaslMechanism.class).orElse(SaslMechanism.PLAIN);
        var ssl = runContext.render(sslEnabled).as(Boolean.class).orElse(false);

        if (mechanism == SaslMechanism.NONE) {
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT");
        } else {
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, ssl ? "SASL_SSL" : "SASL_PLAINTEXT");
            var mechanismName = mechanism == SaslMechanism.SCRAM_SHA_512 ? "SCRAM-SHA-512" : "PLAIN";
            props.put(SaslConfigs.SASL_MECHANISM, mechanismName);

            var rUsername = runContext.render(username).as(String.class).orElse(null);
            var rPassword = runContext.render(password).as(String.class).orElse(null);
            if (rUsername != null && rPassword != null) {
                var loginModule = mechanism == SaslMechanism.SCRAM_SHA_512
                    ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                    : "org.apache.kafka.common.security.plain.PlainLoginModule";
                // The JAAS config string is built from rendered secrets and never stored in a field.
                // Values are escaped so a credential containing `"` or `\` cannot inject extra directives.
                props.put(SaslConfigs.SASL_JAAS_CONFIG,
                    loginModule + " required username=\"" + escapeJaas(rUsername) + "\" password=\"" + escapeJaas(rPassword) + "\";");
            }
        }

        if (ssl) {
            props.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");
        }

        return props;
    }

    /** Escapes backslash and double-quote so the value is safe to embed in a JAAS quoted string. */
    private static String escapeJaas(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Creates a byte-array producer using the shared connection properties. */
    protected KafkaProducer<byte[], byte[]> producer(RunContext runContext) throws IllegalVariableEvaluationException {
        var props = kafkaProperties(runContext);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(props);
    }

    /** Creates a byte-array consumer using the shared connection properties. */
    protected KafkaConsumer<byte[], byte[]> consumer(RunContext runContext, String groupId) throws IllegalVariableEvaluationException {
        var props = kafkaProperties(runContext);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(props);
    }
}
