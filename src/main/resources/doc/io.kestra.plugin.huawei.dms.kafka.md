# How to use the Huawei DMS for Kafka plugin

Sends and receives messages on [Huawei Cloud DMS for Kafka](https://www.huaweicloud.com/product/dms.html) instances using the standard Apache Kafka wire protocol (kafka-clients 3.9).

## Authentication

DMS for Kafka uses SASL authentication with a username and password tied to the DMS instance. Set `saslMechanism` to `PLAIN` (default) or `SCRAM_SHA_512`, then supply `username` and `password`.

Always provide credentials via [Kestra secrets](https://kestra.io/docs/concepts/secret) and configure them in [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) to avoid repeating them on every task:

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.dms.kafka
    values:
      bootstrapServers: "dms-instance-id.kafka.eu-west-101.myhuaweicloud.com:9093"
      saslMechanism: PLAIN
      username: "{{ secret('DMS_KAFKA_USERNAME') }}"
      password: "{{ secret('DMS_KAFKA_PASSWORD') }}"
      sslEnabled: true
```

For VPC-internal clusters without authentication, set `saslMechanism: NONE`.

Secret properties: `username`, `password`.

## Tasks

### Produce

Reads messages from `from` (a single map, a list of maps, or an ION file URI from internal storage) and sends them to a Kafka topic. Supports `STRING`, `JSON`, and `BINARY` serializers for both key and value.

Required properties: `bootstrapServers`, `from`.

Optional per-record overrides inside each map: `topic`, `partition`, `headers`.

### Consume

Polls a topic using a consumer group until `maxRecords` or `maxDuration` is reached — at least one is required. Commits offsets after each batch and writes the consumed records to Kestra internal storage as an ION file at `uri`.

Required properties: `bootstrapServers`, `topic`, `groupId`, and at least one of `maxRecords` / `maxDuration`.

The task uses drain detection: if the topic is exhausted before `maxRecords` is reached and multiple empty polls occur, it exits early to avoid hanging.

## Triggers

### Trigger

Polls the topic on a fixed `interval` (default 60 s) and fires one execution per non-empty batch. Delegates to `Consume` internally. At least one of `maxRecords` or `maxDuration` must be set — validation rejects the flow if both are absent.

Outputs: `{{ trigger.uri }}` (ION file), `{{ trigger.messagesCount }}`.

### RealtimeTrigger

Maintains a persistent Kafka consumer and fires one execution per record. Offset commits happen after each record for at-least-once semantics. The consumer is cleanly stopped via `consumer.wakeup()` when the trigger is killed or stopped.

Outputs: all fields of the `Message` model, e.g. `{{ trigger.value }}`, `{{ trigger.key }}`, `{{ trigger.topic }}`, `{{ trigger.partition }}`, `{{ trigger.offset }}`, `{{ trigger.timestamp }}`, `{{ trigger.headers }}`.
