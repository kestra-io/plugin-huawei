# How to use the Huawei Cloud plugin

Tasks and triggers for Huawei Cloud services, spanning object storage, identity and access management, messaging, data integration, monitoring, serverless functions, CLI access, and notifications. See the full list below.

## Services

- **OBS** (`io.kestra.plugin.huawei.obs`): upload, download, list, copy, delete, batch-delete, create and delete buckets, and trigger flows on new objects.
- **IAM** (`io.kestra.plugin.huawei.iam`): exchange long-lived credentials for short-lived STS credentials.
- **DMS for Kafka** (`io.kestra.plugin.huawei.dms.kafka`): produce, consume, and trigger on messages using the Apache Kafka protocol.
- **DMS for RocketMQ** (`io.kestra.plugin.huawei.dms.rocketmq`): publish, consume, and trigger on messages using the Apache RocketMQ protocol.
- **DataArts Studio** (`io.kestra.plugin.huawei.dataarts`): start, monitor, and stop DataArts Factory batch job runs.
- **CES** (`io.kestra.plugin.huawei.ces`): push custom metrics, query metric statistics, and trigger flows on metric datapoints.
- **FunctionGraph** (`io.kestra.plugin.huawei.functiongraph`): synchronously invoke a FunctionGraph function.
- **KooCLI** (`io.kestra.plugin.huawei.koocli`): run arbitrary Huawei Cloud CLI (`hcloud`) commands in a container.
- **SMN** (`io.kestra.plugin.huawei.smn`): publish notification messages to a topic.
- **DLI** (`io.kestra.plugin.huawei.dli`): run SQL queries against data in OBS or federated sources, the Huawei equivalent of AWS Athena.

## Authentication

Most tasks authenticate with an access key and secret key. Provide them via [Kestra secrets](https://kestra.io/docs/concepts/secret) rather than inline values. When using temporary credentials, also supply the security token.

OBS tasks can obtain short-lived credentials automatically through the `temporaryCredentials` block, set once via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.obs
    values:
      region: eu-west-101
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
```

## Region

All tasks require a `region` (for example `eu-west-101`). Endpoints are derived from the region unless an explicit endpoint override is set.
