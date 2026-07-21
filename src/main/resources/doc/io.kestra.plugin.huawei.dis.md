# How to use the DIS plugin

Writes and consumes records on [Huawei Cloud DIS (Data Ingestion Service)](https://www.huaweicloud.com/product/dis.html) streams, the Huawei equivalent of AWS Kinesis Data Streams.

## Authentication

DIS tasks authenticate using AK/SK request signing (Huawei Cloud HMAC-SHA256). Provide `accessKeyId` and `secretAccessKey` via [Kestra secrets](https://kestra.io/docs/concepts/secret). When working with temporary credentials obtained from IAM, also supply `securityToken`, or configure `temporaryCredentials` for inline IAM credential exchange.

Secret properties: `accessKeyId`, `secretAccessKey`, `securityToken`.

Configure shared defaults via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.dis
    values:
      region: eu-west-101
      projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
```

### `endpointSuffix`

Controls the top-level domain used when deriving the DIS endpoint from `region`. Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the Huawei Cloud European sovereign cloud.

### `endpointOverride`

Overrides the default endpoint derived from `region` and `endpointSuffix`. Use for private endpoints, non-standard deployments, or local tests. When set, `endpointSuffix` is ignored.

## Gotchas

- **`projectId` is required with a custom endpoint**: DIS's v2 APIs embed the project ID in the request path (`/v2/{project_id}/...`). The SDK auto-discovers the project only when resolving the endpoint from its region enum; a custom endpoint (`endpointOverride` or `endpointSuffix`) bypasses that, so every DIS task fails fast requiring `projectId` whenever a custom endpoint is set.
- **`PutRecords` can partially fail on an HTTP 200**: DIS accepts the batch but may reject individual records. `failedRecordCount` and the `uri` output (per-record `partitionId`/`sequenceNumber`/`errorCode`/`errorMessage`) surface exactly which ones failed; `failOnUnsuccessfulRecords` (default `true`) fails the task when any record is rejected.
- **DIS batch limits**: a single record must stay under 1 MB; a single `PutRecords` request is capped at 500 records or 5 MB, whichever is hit first. `PutRecords` chunks larger inputs automatically.
- **No server-side checkpoint API used**: `Trigger` and `RealtimeTrigger` track progress with client-side partition cursors plus a watermark persisted in the flow's namespace [KV Store](https://kestra.io/docs/concepts/kv-store) — they do not use DIS's app/checkpoint API. Deleting the watermark KV entry resets consumption to `startingPosition`.
- **`previewSqlJobResult`-style row caps do not apply here**: unlike DLI, `Consume` streams and writes every fetched record to internal storage (`uri` + `count`); it never returns rows inline.
- **Realtime restarts resume, not restart clean**: `RealtimeTrigger` persists its watermark after each non-empty batch, so a worker restart resumes near the last delivered record instead of re-reading the whole stream from `startingPosition`.

## Tasks

### PutRecords

Writes a batch of records to a stream.

Required: `streamName`, `from` (a map, list of maps, or a `kestra://` ION file URI; each entry needs `data` and either `partitionKey` or `partitionId`, with optional `explicitHashKey`).

Optional: `serdeType` (`STRING` (default), `JSON`, `BINARY` — applied to `data` before base64 encoding), `failOnUnsuccessfulRecords` (default `true`).

Outputs: `recordCount`, `failedRecordCount`, `uri` (ION file of per-record results).

## Triggers and consumption

`Consume`, `Trigger`, and `RealtimeTrigger` all read every partition of `streamName` (or a single `partitionId`, when set), decoding each record's `data` according to `serdeType`. `startingPosition` (`TRIM_HORIZON` default, `LATEST`, `AT_TIMESTAMP` with `startingTimestamp`) controls where a partition starts the first time it has no known position.

### Consume

One-shot task. Reads until `maxRecords` (max 1,000,000) or `maxDuration` (max `PT24H`) is reached, or the stream is caught up — whichever comes first. Always starts fresh from `startingPosition`; does not remember a previous execution.

Outputs: `count`, `uri` (ION file of consumed records).

### Trigger

Polls on a fixed `interval` (default `PT60S`) and fires one execution per non-empty batch. Same bounds as `Consume` (`maxRecords`, `maxDuration`), applied per poll cycle. A per-partition sequence-number watermark persisted in the namespace KV Store ensures records are never re-delivered across overlapping polls.

Outputs: same as `Consume` (`count`, `uri`).

### RealtimeTrigger

Maintains a persistent poll loop and fires one execution per record. No `maxRecords`/`maxDuration` — it runs until the flow's trigger is stopped or killed.

Outputs: one `Record` (`partitionId`, `sequenceNumber`, `partitionKey`, `data`, `timestamp`) per execution.
