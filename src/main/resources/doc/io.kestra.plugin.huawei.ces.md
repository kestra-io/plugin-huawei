# How to use the CES plugin

Pushes custom metrics to and queries metric statistics from [Huawei Cloud CES (Cloud Eye Service)](https://www.huaweicloud.com/product/ces.html), the Huawei equivalent of AWS CloudWatch.

## Authentication

CES tasks authenticate using AK/SK request signing (Huawei Cloud HMAC-SHA256). Provide `accessKeyId` and `secretAccessKey` via [Kestra secrets](https://kestra.io/docs/concepts/secret). When working with temporary credentials obtained from IAM, also supply `securityToken`, or configure `temporaryCredentials` for inline IAM credential exchange.

Secret properties: `accessKeyId`, `secretAccessKey`, `securityToken`.

Configure shared defaults via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.ces
    values:
      region: eu-west-101
      projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
```

### `endpointSuffix`

Controls the top-level domain used when deriving the CES endpoint from `region`. Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the Huawei Cloud European sovereign cloud.

### `endpointOverride`

Overrides the default endpoint derived from `region` and `endpointSuffix`. Use for private endpoints, non-standard deployments, or local tests. When set, `endpointSuffix` is ignored.

## Gotchas

- **Namespace format**: CES namespaces follow `service.item` (e.g. `SYS.ECS`). Custom namespaces used with `Push` must NOT start with `SYS.`, which is reserved for Huawei Cloud system namespaces.
- **Dimensions are mandatory for queries**: `Query` and `Trigger` require between 1 and 3 dimensions (`name`/`value` pairs) — CES's `showMetricData` API mandates at least one dimension (`dim.0`) to identify the exact resource instance. `Push` does not have this constraint; dimensions are optional per metric.
- **Push batch limit**: CES caps a single `createMetricData` request at 10 datapoints. `Push` chunks larger batches automatically.
- **`Query.series` is capped**: at most 1440 of the most recent datapoints are returned, bounding memory usage when `period=RAW` is combined with a large `window`. Narrow `window` or increase `period` for finer-grained coverage over a longer range.

## Tasks

### Push

Pushes one or more custom metric datapoints under a single `namespace`.

Required: `namespace`, `metrics` (list of `metricName`, `value`, and optionally `dimensions`, `unit`, `type`, `collectTime`, `ttl`).

Outputs: `count` (number of datapoints pushed).

### Query

Queries a single metric's datapoints over a time window.

Required: `namespace`, `metricName`, `dimensions` (1 to 3 pairs).

Optional: `statistic` (`AVERAGE`, `MAX`, `MIN`, `SUM`, `VARIANCE`; defaults to `AVERAGE`), `period` (`RAW`, `ONE_MINUTE`, `FIVE_MINUTES`, `TWENTY_MINUTES`, `ONE_HOUR`, `FOUR_HOURS`, `ONE_DAY`; defaults to `FIVE_MINUTES`), `window` (ISO-8601 duration ending now; defaults to `PT1H`).

Outputs: `count` (number of datapoints returned), `series` (list of `timestamp`/`value`/`unit`, sorted ascending by timestamp).

## Triggers

### Trigger

Polls a CES metric on a configurable interval (`interval`, default `PT5M`) and fires an execution when at least one **new** datapoint is found. Accepts the same properties as `Query` plus `interval`, `threshold`, and `comparisonOperator`.

- `threshold` (optional): when set, the trigger only fires if a new datapoint's value satisfies `value <comparisonOperator> threshold`. When omitted, the trigger fires on any new datapoint.
- `comparisonOperator` (optional): `GREATER_THAN` (default), `GREATER_THAN_OR_EQUAL`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `EQUAL`. Ignored when `threshold` is not set.
- **De-duplication**: a watermark (the timestamp of the most recent datapoint seen) is persisted in the flow's namespace [KV Store](https://kestra.io/docs/concepts/kv-store) between polls, so a `window` larger than `interval` (e.g. `window: PT10M` with `interval: PT5M`) never re-fires on the same datapoint.

Outputs: same shape as `Query` (`count`, `series`), containing only the new datapoints that fired this execution.
