package io.kestra.plugin.huawei.dms.kafka;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
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
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractDmsKafka extends AbstractConnection implements DmsKafkaConnectionInterface {

    @PluginProperty(group = "connection")
    protected Property<String> bootstrapServers;

    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<SaslMechanism> saslMechanism = Property.ofValue(SaslMechanism.PLAIN);

    @PluginProperty(group = "connection", secret = true)
    protected Property<String> username;

    @PluginProperty(group = "connection", secret = true)
    protected Property<String> password;

    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<Boolean> sslEnabled = Property.ofValue(false);

    @Builder.Default
    @PluginProperty(group = "processing")
    protected Property<SerdeType> keySerdeType = Property.ofValue(SerdeType.STRING);

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
                props.put(SaslConfigs.SASL_JAAS_CONFIG,
                    loginModule + " required username=\"" + rUsername + "\" password=\"" + rPassword + "\";");
            }
        }

        if (ssl) {
            props.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "https");
        }

        return props;
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
