package io.kestra.plugin.huawei.dms.kafka;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Shared property contract for all DMS for Kafka tasks and triggers.
 */
public interface DmsKafkaConnectionInterface {

    @Schema(
        title = "Kafka bootstrap servers",
        description = "Comma-separated list of `host:port` pairs that the Kafka client uses for the initial " +
            "cluster connection. For DMS for Kafka, copy this value from the instance detail page in the console."
    )
    @NotNull
    @PluginProperty(group = "connection")
    Property<String> getBootstrapServers();

    @Schema(
        title = "SASL mechanism used for authentication",
        description = """
            `PLAIN` — username/password (default, used by most DMS for Kafka instances).
            `SCRAM_SHA_512` — stronger challenge-response, supported on newer instances.
            `NONE` — no SASL; for VPC-internal clusters with no auth enabled.
            """
    )
    @PluginProperty(group = "connection")
    Property<SaslMechanism> getSaslMechanism();

    @Schema(
        title = "SASL username",
        description = "Required when `saslMechanism` is `PLAIN` or `SCRAM_SHA_512`."
    )
    @PluginProperty(group = "connection", secret = true)
    Property<String> getUsername();

    @Schema(
        title = "SASL password",
        description = "Required when `saslMechanism` is `PLAIN` or `SCRAM_SHA_512`. " +
            "**Sensitive — always provide via `{{ secret('NAME') }}`.**"
    )
    @PluginProperty(group = "connection", secret = true)
    Property<String> getPassword();

    @Schema(
        title = "Enable TLS for the Kafka connection",
        description = "Set to `true` to use `SASL_SSL` instead of `SASL_PLAINTEXT`. " +
            "DMS for Kafka instances accessed over the public internet require TLS."
    )
    @PluginProperty(group = "connection")
    Property<Boolean> getSslEnabled();

    @Schema(
        title = "Key serializer/deserializer type",
        description = "`STRING` (default), `JSON`, or `BINARY`."
    )
    @PluginProperty(group = "processing")
    Property<SerdeType> getKeySerdeType();

    @Schema(
        title = "Value serializer/deserializer type",
        description = "`STRING` (default), `JSON`, or `BINARY`."
    )
    @PluginProperty(group = "processing")
    Property<SerdeType> getValueSerdeType();
}
