package io.kestra.plugin.huawei.dms.kafka;

/**
 * SASL mechanism used when connecting to DMS for Kafka.
 *
 * <p>DMS for Kafka supports PLAIN and SCRAM-SHA-512 over SASL_PLAINTEXT (TLS optional).
 * Use {@code NONE} for VPC-internal deployments where no authentication is required.
 */
public enum SaslMechanism {
    /** SASL PLAIN — username/password in clear, used by default on DMS for Kafka instances. */
    PLAIN,
    /** SASL SCRAM-SHA-512 — stronger challenge-response; supported on newer DMS for Kafka instances. */
    SCRAM_SHA_512,
    /** No SASL — plain PLAINTEXT protocol; for VPC-internal clusters with no auth. */
    NONE
}
