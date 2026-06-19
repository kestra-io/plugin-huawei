# How to use the Huawei DMS for RocketMQ plugin

Publishes and consumes messages on [Huawei Cloud DMS for RocketMQ](https://www.huaweicloud.com/product/dms.html) instances using the Apache RocketMQ 4.x client with ACL-based authentication.

## Authentication

DMS for RocketMQ uses AK/SK (access key / secret key) credentials passed to the client via an `AclClientRPCHook`. Set `accessKeyId` and `secretAccessKey` on every task. For instances without ACL enabled, omit both properties and the client connects without credentials.

Always provide credentials via [Kestra secrets](https://kestra.io/docs/concepts/secret) and configure them in [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.dms.rocketmq
    values:
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
      nameServerAddr: "dms-instance-id.rocketmq.eu-west-101.myhuaweicloud.com:8100"
```

When the DMS instance uses instance isolation, also set `instanceId` (the DMS instance ID shown in the console). This maps to the RocketMQ namespace and ensures topics are scoped to the correct instance.

Secret properties: `accessKeyId`, `secretAccessKey`.

## Tasks

### Publish

Reads messages from `from` (a single map, a list of maps, or an ION file URI from internal storage) and sends them to a RocketMQ topic synchronously. Each map must contain `body`; optional fields are `tags` and `keys`. Supports `STRING` and `JSON` body serializers. The task only completes once all messages have been acknowledged by the broker (`SEND_OK`).

Required properties: `nameServerAddr`, `topic`, `groupId`, `from`.

### Consume

Polls the topic in pull mode until `maxRecords` or `maxDuration` is reached — at least one is required. Supports server-side tag filtering via `tags` (default `*`). Consumed messages are written to Kestra internal storage as an ION file at `uri`.

Note: `DefaultMQPullConsumer` is deprecated in RocketMQ 4.x but remains the only viable pull-mode API available without migrating to 5.x. This is a deliberate tradeoff — the push consumer (`DefaultMQPushConsumer`) does not provide a bounded batch interface compatible with Kestra's polling trigger model.

Required properties: `nameServerAddr`, `topic`, `groupId`, and at least one of `maxRecords` / `maxDuration`.

## Triggers

### Trigger

Polls the topic on a fixed `interval` (default 60 s) and fires one execution per non-empty batch. Delegates to `Consume` internally. At least one of `maxRecords` or `maxDuration` must be set — validation rejects the flow if both are absent.

Outputs: `{{ trigger.uri }}` (ION file), `{{ trigger.messagesCount }}`.

### RealtimeTrigger

Registers a push listener (`DefaultMQPushConsumer`) and fires one execution per message. The consumer is cleanly shut down via `shutdown()` when the trigger is stopped or killed.

Outputs: all fields of the `Message` model, e.g. `{{ trigger.body }}`, `{{ trigger.messageId }}`, `{{ trigger.topic }}`, `{{ trigger.tags }}`, `{{ trigger.keys }}`, `{{ trigger.bornTimestamp }}`.
