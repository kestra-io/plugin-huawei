# Kestra Huawei Plugin

## What

Provides Kestra plugin tasks and shared abstractions for Huawei Cloud services under `io.kestra.plugin.huawei`.

## Why

Teams using Huawei Cloud need first-class Kestra integrations for storage, authentication, and future services without hand-rolling HTTP calls or managing SDK lifecycle themselves.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.huawei`:

- `io.kestra.plugin.huawei` — plugin-wide abstractions (`AbstractConnection`, `AbstractConnectionInterface`, `ConnectionUtils`, `TemporaryCredentialsConfig`)
- `io.kestra.plugin.huawei.iam` — IAM authentication tasks (`GetTemporaryCredentials`)
- `io.kestra.plugin.huawei.obs` — OBS shared layer (`AbstractObs`, `AbstractObsObject`, `AbstractObsInterface`, `AbstractObsTrigger`, `AuthType`, `ListInterface`, `ObsUtils`, `ObsService`) and object tasks (`Upload`, `Download`, `List`, `Copy`, `Delete`, `DeleteList`, `CreateBucket`, `DeleteBucket`, `Downloads`, `Trigger`)
- `io.kestra.plugin.huawei.obs.models` — serializable output models (`ObsObject`)
- `io.kestra.plugin.huawei.dms.kafka` — DMS for Kafka tasks/triggers (`AbstractDmsKafka`, `DmsKafkaConnectionInterface`, `SaslMechanism`, `SerdeType`, `Produce`, `Consume`, `Trigger`, `RealtimeTrigger`)
- `io.kestra.plugin.huawei.dms.kafka.models` — DMS Kafka output models (`Message`)
- `io.kestra.plugin.huawei.dms.rocketmq` — DMS for RocketMQ tasks/triggers (`AbstractDmsRocketMq`, `DmsRocketMqConnectionInterface`, `RocketMqSerdeType`, `Publish`, `Consume`, `Trigger`, `RealtimeTrigger`)
- `io.kestra.plugin.huawei.dms.rocketmq.models` — DMS RocketMQ output models (`Message`)
- `io.kestra.plugin.huawei.dataarts` — DataArts Studio tasks (`AbstractDataArts`, `DataArtsConnectionInterface`, `DataArtsUtils`, `DataArtsService`, `StartJobRun`, `GetJobRun`, `StopJobRun`)
- `io.kestra.plugin.huawei.dataarts.models` — DataArts output models (`JobRun`)
- `io.kestra.plugin.huawei.functiongraph` — FunctionGraph tasks (`AbstractFunctionGraph`, `FunctionGraphConnectionInterface`, `FunctionGraphUtils`, `FunctionGraphInvokeException`, `Invoke`)
- `io.kestra.plugin.huawei.koocli` — KooCLI tasks (`KooCLI`)
- `io.kestra.plugin.huawei.ces` — CES (Cloud Eye Service) tasks/trigger (`AbstractCes`, `AbstractCesTrigger`, `CesConnectionInterface`, `CesUtils`, `Dimension`, `Push`, `Query`, `Trigger`)
- `io.kestra.plugin.huawei.smn` — SMN (Simple Message Notification) task (`AbstractSmn`, `SmnConnectionInterface`, `SmnUtils`, `Publish`)
- `io.kestra.plugin.huawei.dli` — DLI (Data Lake Insight) task (`AbstractDli`, `DliConnectionInterface`, `DliUtils`, `DliService`, `Query`)
- `io.kestra.plugin.huawei.eventgrid` — EventGrid (EG) task (`AbstractEventGrid`, `EventGridConnectionInterface`, `EventGridUtils`, `PutEvents`)
- `io.kestra.plugin.huawei.dis` — DIS (Data Ingestion Service) tasks/triggers (`AbstractDis`, `AbstractDisTrigger`, `ConsumeOptionsInterface`, `DisConnectionInterface`, `DisUtils`, `DisService`, `DisWatermark`, `SerdeType`, `StartingPosition`, `PutRecords`, `Consume`, `Trigger`, `RealtimeTrigger`)
- `io.kestra.plugin.huawei.dis.models` — DIS output models (`Record`)
- `io.kestra.plugin.huawei.geminidb` — GeminiDB for NoSQL (DynamoDB-Compatible API) tasks (`AbstractGeminiDb`, `PutItem`, `GetItem`, `DeleteItem`, `Query`, `Scan`)
- `io.kestra.plugin.huawei.swr` — SWR (Software Repository for Container) task (`AbstractSwr`, `SwrConnectionInterface`, `SwrUtils`, `GetAuthToken`)

Infrastructure dependencies (Docker Compose services):

- `app` (`docker-compose.yml`) — Kestra application for manual plugin testing
- `minio` (`docker-compose-ci.yml`) — S3-compatible object storage for integration tests (ports 9000/9001, credentials minioadmin/minioadmin); started in CI by `.github/setup-unit.sh`, kept out of the production `docker-compose.yml`
- `kafka` (`docker-compose-ci.yml`) — KRaft-mode Kafka broker for DMS Kafka integration tests (port 9092, no auth)
- `rocketmq-namesrv` / `rocketmq-broker` (`docker-compose-ci.yml`) — Apache RocketMQ 4.9.4 for DMS RocketMQ integration tests (namesrv port 9876, broker port 10911); the 5.5.0 client is backward-compatible with the 4.x broker
- `dynamodb-local` (`docker-compose-ci.yml`) — `amazon/dynamodb-local` standing in for GeminiDB's DynamoDB-compatible data-plane API in unit tests (port 8000, in-memory, shared DB); started unconditionally by `.github/setup-unit.sh` so the default-path GeminiDB tests run without any env gate

### Key Plugin Classes

**Auth / IAM**

- `io.kestra.plugin.huawei.ConnectionUtils` — Static factory for Huawei Cloud SDK credentials (`projectCredentials`, `globalCredentials`) and clients (`iamClient`, `iamClientWithToken`); also exposes `exchangeForTemporaryCredentials(RunContext, TemporaryCredentialsConfig, region, endpointOverride)` which drives both the inline connection-layer exchange and the `GetTemporaryCredentials` task
- `io.kestra.plugin.huawei.TemporaryCredentialsConfig` — Nested configuration block for inline IAM credential exchange; holds `authMethod`, `iamToken` (TOKEN path), `username`/`password`/`domainName` (PASSWORD path), `scope`, `projectName`, `durationSeconds`; set as `temporaryCredentials` on any connection
- `io.kestra.plugin.huawei.iam.GetTemporaryCredentials` — **Escape-hatch task**: obtains short-lived STS credentials and exposes them as task outputs (`accessKeyId`, `secretAccessKey`, `securityToken`, `expirationTime`) for manual wiring into downstream tasks or external systems; delegates to `ConnectionUtils.exchangeForTemporaryCredentials`

**OBS**

- `io.kestra.plugin.huawei.obs.Upload` — Uploads a file from Kestra internal storage to OBS
- `io.kestra.plugin.huawei.obs.Download` — Downloads an OBS object into Kestra internal storage
- `io.kestra.plugin.huawei.obs.List` — Lists OBS objects with prefix/regexp filtering, full pagination
- `io.kestra.plugin.huawei.obs.Copy` — Server-side copy of an OBS object within or between buckets; `delete=true` for move semantics
- `io.kestra.plugin.huawei.obs.Delete` — Deletes a single OBS object by bucket/key (and optional versionId)
- `io.kestra.plugin.huawei.obs.DeleteList` — Batch-deletes all objects matching a prefix/regexp filter in chunks of 1000
- `io.kestra.plugin.huawei.obs.CreateBucket` — Creates an OBS bucket; idempotent if the caller already owns it
- `io.kestra.plugin.huawei.obs.DeleteBucket` — Deletes an empty OBS bucket; idempotent by default (`errorOnMissing=false`) if the bucket is absent
- `io.kestra.plugin.huawei.obs.Downloads` — Lists matching objects, downloads each to Kestra storage, applies NONE/DELETE/MOVE action
- `io.kestra.plugin.huawei.obs.Trigger` — Polling trigger that fires when new objects appear in a bucket; applies action after download to avoid re-triggering

**DMS for Kafka**

- `io.kestra.plugin.huawei.dms.kafka.Produce` — Sends messages to a DMS Kafka topic; supports STRING/JSON/BINARY serialization and per-record header/partition overrides
- `io.kestra.plugin.huawei.dms.kafka.Consume` — Polls a DMS Kafka topic until `maxRecords`/`maxDuration`; writes ION to internal storage; commits offsets on exit
- `io.kestra.plugin.huawei.dms.kafka.Trigger` — Polling trigger delegating to `Consume`; fires when new records are found
- `io.kestra.plugin.huawei.dms.kafka.RealtimeTrigger` — Persistent consumer; fires one execution per record; `kill()`/`stop()` via `AtomicBoolean + CountDownLatch + consumer.wakeup()`

**DMS for RocketMQ**

- `io.kestra.plugin.huawei.dms.rocketmq.Publish` — Sends messages to a DMS RocketMQ topic via `DefaultMQProducer`; supports STRING/JSON body serialization
- `io.kestra.plugin.huawei.dms.rocketmq.Consume` — Pull-mode loop until `maxRecords`/`maxDuration`; writes ION to internal storage
- `io.kestra.plugin.huawei.dms.rocketmq.Trigger` — Polling trigger delegating to `Consume`; fires when new messages are found
- `io.kestra.plugin.huawei.dms.rocketmq.RealtimeTrigger` — Push consumer via `DefaultMQPushConsumer`; fires one execution per message; stops via `CountDownLatch`

**DataArts Studio**

- `io.kestra.plugin.huawei.dataarts.StartJobRun` — Starts a DataArts Factory (DLF) batch job; resolves the new instance by querying the instance list (the start API returns 204 with no ID); optionally polls until terminal state
- `io.kestra.plugin.huawei.dataarts.GetJobRun` — Fetches status and metadata of a DataArts Factory job run by `instanceId` or resolves the latest instance when `instanceId` is omitted
- `io.kestra.plugin.huawei.dataarts.StopJobRun` — Stops a running DataArts Factory job run instance; optionally polls until `manual-stop` is confirmed
- `io.kestra.plugin.huawei.dataarts.DataArtsService` — Static REST helpers for the DataArts Factory V1 API; uses `AKSKSigner.getInstance().sign(request, credentials)` from the SDK core for HMAC-SHA256 signing; JDK `HttpClient` for transport
- `io.kestra.plugin.huawei.dataarts.DataArtsUtils` — Static endpoint resolution (`endpointOverride` → region-derived → throws); mirrors `ObsUtils`

**FunctionGraph**

- `io.kestra.plugin.huawei.functiongraph.Invoke` — Synchronously invokes a FunctionGraph function; sends optional `functionPayload` as the event body; stores response in Kestra internal storage; throws `FunctionGraphInvokeException` on function-level errors (status=1) or HTTP errors
- `io.kestra.plugin.huawei.functiongraph.AbstractFunctionGraph` — Base class extending `AbstractConnection`; builds `FunctionGraphClient` using `FunctionGraphRegion.valueOf(region)` (with fallback to `withEndpoint` for unknown regions) or direct `endpointOverride`; validates AK/SK completeness
- `io.kestra.plugin.huawei.functiongraph.FunctionGraphUtils` — Static endpoint resolution (`endpointOverride` → region+suffix-derived → throws); supports `endpointSuffix` for EU sovereign cloud (`myhuaweicloud.eu`)
- `io.kestra.plugin.huawei.functiongraph.FunctionGraphInvokeException` — Unchecked exception for function-level and HTTP-level invocation failures

**KooCLI**

- `io.kestra.plugin.huawei.koocli.KooCLI` — Runs arbitrary `hcloud` CLI commands in a container (default `ubuntu:26.04`); injects AK/SK, region, and security token as env vars, then writes a `default` profile via a guarded `hcloud configure set` step that references them by shell name so secrets never reach argv or logs; auto-installs `hcloud` when absent (two guarded bootstrap steps: curl, then the install script chosen by the 3-tier `resolveInstallScriptUrl(...)`); supports `temporaryCredentials` inline exchange; returns `ScriptOutput` with `vars`, `outputFiles`, `exitCode`

**CES (Cloud Eye Service)**

- `io.kestra.plugin.huawei.ces.Push` — Pushes custom metric datapoints via `createMetricData`; namespace must NOT use the reserved `SYS.` prefix; chunks batches over 10 datapoints (CES per-request cap)
- `io.kestra.plugin.huawei.ces.Query` — Queries metric statistics via `showMetricData`; requires 1 to 3 dimensions (CES mandates at least one, `dim.0`, to identify the resource instance); `window` (default `PT1H`) is converted to `from`/`to` epoch-millisecond bounds ending now; returns `series` sorted by timestamp ascending, capped at `Query.MAX_SERIES_SIZE` (1440) most-recent datapoints to bound memory when `period=RAW`
- `io.kestra.plugin.huawei.ces.Trigger` — Polling trigger delegating to `Query`; fires when at least one *new* datapoint is found (optionally matching `threshold`/`comparisonOperator`, default `GREATER_THAN`); persists a watermark (last-seen datapoint timestamp) in the flow's namespace KV Store (`runContext.namespaceKv(namespace)`, key `ces_trigger_watermark_<flowId>_<triggerId>`) so overlapping `window`/`interval` combinations never re-fire on the same datapoint
- `io.kestra.plugin.huawei.ces.AbstractCes` — Base class extending `AbstractConnection`; builds `CesClient` using `CesRegion.valueOf(region)` (with fallback to `withEndpoint` for unknown regions) or direct `endpointOverride`; validates AK/SK completeness
- `io.kestra.plugin.huawei.ces.AbstractCesTrigger` — Connection-aware base for CES triggers extending `AbstractTrigger` and implementing `CesConnectionInterface`; holds the shared connection + endpoint properties (mirrors `AbstractObsTrigger`) so `Trigger` (and any future CES trigger) inherits them instead of re-declaring each one
- `io.kestra.plugin.huawei.ces.CesUtils` — Static endpoint resolution (`endpointOverride` → region+suffix-derived → throws) plus `service.item` namespace format validation (`validateNamespaceFormat`, `validateCustomNamespace`)
- `io.kestra.plugin.huawei.ces.Dimension` — Shared `name`/`value` pair used by both `Push` (per-metric dimensions) and `Query`/`Trigger` (resource-identifying dimensions), mapped to CES's `dim.0`/`dim.1`/`dim.2` query parameters as `name,value` strings

**SMN (Simple Message Notification)**

- `io.kestra.plugin.huawei.smn.Publish` — Publishes exactly one message to an SMN topic via `publishMessage`, the Huawei Cloud equivalent of `io.kestra.plugin.aws.sns.Publish`; requires exactly one of `message` (plain text), `messageStructure` (per-protocol `Property<Map<String, Object>>`, serialized to the JSON string SMN expects), or `messageTemplateName` (+ `tags` to fill its placeholders); `subject` applies only to `email` subscriptions; optional `messageAttributes` (name/type/value, only `STRING` type currently supported) and `timeToLive` (`Property<Integer>` seconds, validated to SMN's 1–86400 range, sent to the SDK as a String); wraps `ServiceResponseException`/`SdkException` with actionable messages (`remediationHint` maps `SMN.0076`/`0027`/`0021` to console-specific next steps) and returns `Output(messageId, requestId)`
- `io.kestra.plugin.huawei.smn.AbstractSmn` — Base class extending `AbstractConnection`; builds `SmnClient` using the CES-style suffix-first endpoint ordering: `endpointOverride` → explicit `endpointSuffix` (forces suffix-derived resolution even for regions in the SDK enum) → `SmnRegion.valueOf(region)` → region-derived fallback; fails fast requiring `projectId` whenever a custom endpoint is used (SMN v2 APIs embed `{project_id}` in the request path)
- `io.kestra.plugin.huawei.smn.SmnUtils` — Static endpoint resolution (`endpointOverride` → region+suffix-derived → throws) plus `requireProjectIdForCustomEndpoint` validation

**DLI (Data Lake Insight)**

- `io.kestra.plugin.huawei.dli.Query` — Huawei Cloud equivalent of `io.kestra.plugin.aws.athena.Query`; submits a SQL statement via `createSqlJob`, polls `showSqlJobStatus` until terminal (`FINISHED`/`FAILED`/`CANCELLED`), then handles the result per `fetchType`: `STORE` (default) submits an `export-result` job to OBS and reads the exported newline-delimited JSON back into ION in Kestra internal storage; `FETCH`/`FETCH_ONE` read directly from `previewSqlJobResult` (hard-capped at 1000 rows); `NONE` returns immediately after the job completes. Non-`QUERY` job types (`DDL`/`INSERT`/`DCL`/…) never fetch a result set regardless of `fetchType`. Overrides `kill()` to cancel the in-flight DLI job (query or export) via `cancelSqlJob`
- `io.kestra.plugin.huawei.dli.AbstractDli` — Base class extending `AbstractConnection`; builds `DliClient` using the CES-style suffix-first endpoint ordering: `endpointOverride` → explicit `endpointSuffix` (forces suffix-derived resolution even for regions in the SDK enum) → `DliRegion.valueOf(region)` → region-derived fallback; fails fast requiring `projectId` whenever a custom endpoint is used (DLI v1 APIs embed `{project_id}` in the request path)
- `io.kestra.plugin.huawei.dli.DliUtils` — Static endpoint resolution (`endpointOverride` → region+suffix-derived → throws) plus `requireProjectIdForCustomEndpoint` validation
- `io.kestra.plugin.huawei.dli.DliService` — Static SQL-job helpers: submit, poll-until-terminal, preview, export-result submission, best-effort `cancelQuietly`, and the OBS read-back that parses exported ND-JSON into ION

**EventGrid (EG)**

- `io.kestra.plugin.huawei.eventgrid.PutEvents` — Huawei Cloud equivalent of `io.kestra.plugin.aws.eventbridge.PutEvents`; publishes one or more CloudEvents-1.0 events to an EventGrid custom channel via `putEvents`; `events` accepts either an inline list or a `kestra://` internal storage URI of ION-serialized events; auto-generates `id` (random UUID) and defaults `specversion` to `1.0` when omitted; writes per-event `index`/`eventId`/`errorCode`/`errorMsg` results to internal storage as ION; throws when `failOnUnsuccessfulEvents` (default `true`) and at least one event is rejected, otherwise reports `WARNING` on partial failure; always sends the whole `events` list in a single request (EG's per-request batch cap is undocumented, so no chunking is attempted)
- `io.kestra.plugin.huawei.eventgrid.AbstractEventGrid` — Base class extending `AbstractConnection`; builds `EgClient` using the CES-style suffix-first endpoint ordering: `endpointOverride` → explicit `endpointSuffix` (forces suffix-derived resolution even for regions in the SDK enum) → `EgRegion.valueOf(region)` → region-derived fallback; fails fast requiring `projectId` whenever a custom endpoint is used (EG v1 APIs embed `{project_id}` in the request path)
- `io.kestra.plugin.huawei.eventgrid.EventGridUtils` — Static endpoint resolution (`endpointOverride` → region+suffix-derived → throws) plus `requireProjectIdForCustomEndpoint` validation

**DIS (Data Ingestion Service)**

- `io.kestra.plugin.huawei.dis.PutRecords` — Writes a batch of records to a stream via `sendRecords` (wraps `PutRecordsRequest`), the Huawei Cloud equivalent of `io.kestra.plugin.aws.kinesis.PutRecords`; accepts inline maps or a `kestra://` ION file via `io.kestra.core.models.property.Data`/`Data.From`; each record needs `data` and either `partitionKey` or `partitionId` (+ optional `explicitHashKey`); chunks batches to DIS's 500-record/5 MB-per-request limits and fails fast on any single record over 1 MB; DIS can reject individual records within an HTTP 200 response, so `failedRecordCount` and the `uri` output (per-record results) surface partial failures, and `failOnUnsuccessfulRecords` (default `true`) fails the task on any rejection
- `io.kestra.plugin.huawei.dis.Consume` — One-shot task reading every partition of a stream (or a single `partitionId`) via `consumeRecords`/`showCursor`; loops round-robin across partitions until `maxRecords`/`maxDuration` or a full round returns zero records (caught up); always starts from `startingPosition` (`TRIM_HORIZON` default/`LATEST`/`AT_TIMESTAMP`) since it has no persisted state across executions; writes ND-decoded records to ION at `uri`
- `io.kestra.plugin.huawei.dis.Trigger extends AbstractDisTrigger` — Polling trigger delegating to `Consume`'s shared `poll(...)` helper; persists a per-partition sequence-number watermark in the flow's namespace KV Store (key `dis_trigger_watermark_<flowId.length()>_<flowId>_<triggerId>`) so overlapping polls never re-deliver a record
- `io.kestra.plugin.huawei.dis.RealtimeTrigger extends AbstractDisTrigger` — Persistent poll loop across every partition; fires one execution per record; persists the same KV Store watermark after each non-empty batch so a restart resumes near where it left off instead of re-reading the whole stream; `kill()`/`stop()` via `AtomicBoolean` + `CountDownLatch`
- `io.kestra.plugin.huawei.dis.AbstractDis` — Base class extending `AbstractConnection`; delegates client construction to `DisService.buildClient(...)` (suffix-first endpoint ordering, mirrors `AbstractDli`/`AbstractSmn`; fails fast requiring `projectId` on a custom endpoint — DIS v2 APIs embed `{project_id}` in the request path)
- `io.kestra.plugin.huawei.dis.AbstractDisTrigger` — Connection-aware base for both DIS triggers; holds the shared connection/endpoint properties and the same `client(RunContext)` factory (via `DisService.buildClient`) so `Trigger` and `RealtimeTrigger` inherit them instead of re-declaring each one
- `io.kestra.plugin.huawei.dis.DisService` — Package-private static helpers: `buildClient(...)` (shared by both base classes), `listPartitionIds(...)` (paginated via `hasMorePartitions`), `cursorFor(...)` (resolves a partition cursor from `startingPosition`/`startingTimestamp`, or resumes `AFTER_SEQUENCE_NUMBER` when a watermark is supplied)
- `io.kestra.plugin.huawei.dis.DisWatermark` — Package-private KV Store helpers shared by `Trigger` and `RealtimeTrigger`: builds the length-prefixed watermark key and reads/writes the per-partition sequence-number map
- `io.kestra.plugin.huawei.dis.DisUtils` — Static endpoint resolution (`endpointOverride` → region+suffix-derived → throws) plus `requireProjectIdForCustomEndpoint` validation
- `io.kestra.plugin.huawei.dis.SerdeType` — `STRING`/`JSON`/`BINARY` serialization for the record `data` payload, applied before/after DIS's own base64 wire encoding
- `io.kestra.plugin.huawei.dis.StartingPosition` — `TRIM_HORIZON`/`LATEST`/`AT_TIMESTAMP`, mirroring DIS's `ShowCursorRequest.CursorTypeEnum` subset relevant to a first read

**GeminiDB for NoSQL (DynamoDB-Compatible API)**

- `io.kestra.plugin.huawei.geminidb.PutItem` — Upserts an item (`item` map) into a table; Huawei Cloud GeminiDB equivalent of `io.kestra.plugin.aws.dynamodb.PutItem`; `VoidOutput`
- `io.kestra.plugin.huawei.geminidb.GetItem` — Retrieves a single item by `key`; returns an empty `row` map (not an error) when the key doesn't match any item
- `io.kestra.plugin.huawei.geminidb.DeleteItem` — Deletes a single item by `key`; no condition expression, `VoidOutput` (matches `io.kestra.plugin.aws.dynamodb.DeleteItem`, which also doesn't request `ReturnValues`)
- `io.kestra.plugin.huawei.geminidb.Query` — Queries via `keyConditionExpression` + `expressionAttributeValues`, optional `filterExpression`; bounded `limit` (1-1000, default 100); `fetchType` (`STORE` default/`FETCH`/`FETCH_ONE`/`NONE`) → `FetchOutput`. Reads a single response page — does not follow `LastEvaluatedKey` — and logs an INFO message when the response was truncated
- `io.kestra.plugin.huawei.geminidb.Scan` — Scans a table with optional `filterExpression` + `expressionAttributeValues`; same bounded `limit`/`fetchType`/truncation-logging behavior as `Query`
- `io.kestra.plugin.huawei.geminidb.AbstractGeminiDb` — Base class extending `AbstractConnection`; builds a `DynamoDbClient` (AWS SDK v2) via `.endpointOverride(URI)` pointed at the mandatory `endpoint` property (a per-instance connection address — GeminiDB has no region-derived host, unlike every other Huawei service in this plugin) and `StaticCredentialsProvider` (AK/SK, or `AwsSessionCredentials` when `securityToken` is set); `region` is required by the SDK's SigV4 signer but is signing-only and defaults to a placeholder (`cn-north-1`) — it has no effect on request routing; also holds the ported AWS-`AttributeValue`⇄`Object` conversion helpers and the shared `fetchOutputs`/`warnIfTruncated` logic used by `Query`/`Scan`

**SWR (Software Repository for Container)**

- `io.kestra.plugin.huawei.swr.GetAuthToken` — Huawei Cloud equivalent of `io.kestra.plugin.aws.ecr.GetAuthToken`; fetches a short-lived Docker/OCI registry credential via `createSecret`; prefers decoding `auths.<registry-host>.auth` (base64 `username:password`) over parsing the human-readable `X-Swr-Dockerlogin` header, falling back to the header only when `auths` is empty; returns `Output(username, password [EncryptedString], registry, expiry)`. Optional `projectName` (SWR `projectname` query param) defaults to `region`
- `io.kestra.plugin.huawei.swr.AbstractSwr` — Base class extending `AbstractConnection`; builds `SwrClient` using the CES-style suffix-first endpoint ordering: `endpointOverride` → explicit `endpointSuffix` (forces suffix-derived resolution even for regions in the SDK enum) → `SwrRegion.valueOf(region)` → region-derived fallback; fails fast requiring AK/SK completeness — **no** `projectId` fail-fast on a custom endpoint (see Local rules)
- `io.kestra.plugin.huawei.swr.SwrUtils` — Static endpoint resolution (`endpointOverride` → region+suffix-derived → throws); no `requireProjectIdForCustomEndpoint` (createSecret's path has no `{project_id}` segment)

### Inline Temporary Credentials via `pluginDefaults`

The preferred way to use short-lived credentials is the `temporaryCredentials` nested block on any OBS (or future) task. Set it once via `pluginDefaults` and every task in the namespace obtains fresh STS credentials without manual output wiring:

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.obs
    values:
      region: eu-west-101
      temporaryCredentials:
        authMethod: PASSWORD
        username: my-iam-user
        password: "{{ secret('HUAWEI_IAM_PASSWORD') }}"
        domainName: my-account-domain
        durationSeconds: 3600

tasks:
  - id: upload
    type: io.kestra.plugin.huawei.obs.Upload
    bucket: my-bucket
    from: "{{ inputs.file }}"
    key: uploads/data.csv
```

The `GetTemporaryCredentials` task is an escape hatch for the rare cases where you need the raw credential values in subsequent steps or external systems.

**Limitation:** the exchange runs once per task execution. For `RealtimeTrigger` or long-running `Consume` tasks that outlive `durationSeconds`, the temporary credentials will expire mid-run. Use long-lived AK/SK properties or schedule a refresh externally in that case.

### Connection-Layer Credential Exchange

`AbstractConnectionInterface.huaweiClientConfig(RunContext)` checks for `temporaryCredentials` before falling back to static AK/SK properties. The exchange path:

1. `huaweiClientConfig` renders `temporaryCredentials` as `TemporaryCredentialsConfig`
2. Delegates to `ConnectionUtils.exchangeForTemporaryCredentials(runContext, config, region, endpointOverride)`
3. For PASSWORD method: `POST /v3/auth/tokens` via `java.net.http.HttpClient` → returns `X-Subject-Token` header
4. For both methods: `IamClient.createTemporaryAccessKeyByToken(...)` → STS credential in response body
5. Returns `HuaweiClientConfig` populated with the temporary AK/SK + security token

A second overload `huaweiClientConfig(RunContext, String iamEndpointOverride)` accepts an IAM endpoint override; `null` uses the production endpoint derived from `region`. Tests use this overload to redirect IAM calls to WireMock.

### Shared OBS Layer

- `AbstractObs extends AbstractConnection implements AbstractObsInterface` — holds `endpointOverride`, `pathStyleAccess`, `authType`; exposes `protected ObsClient client(RunContext)` factory
- `AbstractObsTrigger extends AbstractTrigger implements AbstractConnectionInterface, AbstractObsInterface` — mirrors `AbstractObs` for triggers; holds all connection fields (including `temporaryCredentials`) and the same `client(RunContext)` factory
- `AbstractObsObject extends AbstractObs` — adds `bucket` property shared by all single-object tasks
- `ObsUtils` — static endpoint resolution (override → region-derived → throws)
- `ObsService` — static helpers: `buildClient(...)` (shared client factory for tasks and Trigger), `download(...)` (buffers via temp file → internal storage), and `list(...)` (paginated, optional regexp filter)
- `AuthType` enum — `OBS` / `V2` / `V4` wrapping SDK `AuthTypeEnum`; use `V2` for MinIO/S3-compatible endpoints
- `ListInterface` — shared property schema (`prefix`, `delimiter`, `marker`, `maxKeys`, `regexp`) implemented by List, DeleteList, Downloads, and Trigger
- `ActionInterface` — shared post-download action schema (`action` enum: NONE/DELETE/MOVE; `moveTo.bucket`, `moveTo.keyPrefix`) implemented by Downloads and Trigger

### Integration Tests

**OBS integration tests** require MinIO. Start it with:

```bash
docker compose -f docker-compose-ci.yml up -d minio
```

Then run:

```bash
OBS_MINIO_TESTS=true ./gradlew test
```

**DMS Kafka integration tests** require a Kafka broker:

```bash
docker compose -f docker-compose-ci.yml up -d kafka
DMS_KAFKA_TESTS=true DMS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./gradlew test
```

**DMS RocketMQ integration tests** require RocketMQ namesrv and broker:

```bash
docker compose -f docker-compose-ci.yml up -d rocketmq-namesrv rocketmq-broker
DMS_ROCKETMQ_TESTS=true DMS_ROCKETMQ_NAME_SERVER=localhost:9876 ./gradlew test
```

All tests above are guarded by `@EnabledIfEnvironmentVariable` so they are skipped unless the matching gate variable is set.

**GeminiDB tests** run against DynamoDB Local (GeminiDB's DynamoDB-compatible data-plane API has no Huawei-specific test double, but DynamoDB Local speaks the exact same wire protocol) and require **no env gate** — they always run as part of the default-path suite:

```bash
docker compose -f docker-compose-ci.yml up -d dynamodb-local
./gradlew test
```

Point at a real GeminiDB instance instead by setting `GEMINIDB_TEST_ENDPOINT` (plus `GEMINIDB_TEST_ACCESS_KEY`, `GEMINIDB_TEST_SECRET_KEY`, `GEMINIDB_TEST_REGION`). A separate `GEMINIDB_TESTS=true` gate is reserved for any future live-cloud-only test; none exists yet since DynamoDB Local already exercises the same code path.

To run OBS tests against a live Huawei Cloud OBS endpoint instead of MinIO, set `OBS_TEST_ENDPOINT` (plus `OBS_TEST_ACCESS_KEY`, `OBS_TEST_SECRET_KEY`, `OBS_TEST_AUTH_TYPE`, `OBS_TEST_PATH_STYLE`, and optionally `OBS_TEST_BUCKET` to reuse a pre-created bucket).

IAM/WireMock tests run without any env gate:

```bash
./gradlew test
```

In CI, `.github/setup-unit.sh` decides the target:

- When the org secrets `HUAWEI_ACCESS_KEY` / `HUAWEI_SECRET_ACCESS_KEY` are present (provisioned by `kestra-io/flows-engineering`, `terraform/huawei-unittest.tf`; visibility `all` for `kestra-io/plugin-*`), it points the tests at **real Huawei Cloud OBS** (region `eu-west-101`, pre-created bucket `kestra-unit-test`) by exporting the `OBS_TEST_*` variables and the gate to `$GITHUB_ENV`. Because the IAM user uses that single bucket, `CreateBucketTest`/`DeleteBucketTest` self-skip (via `OBS_TEST_BUCKET`) and the rest rely on per-test prefix isolation.
- Otherwise (e.g. fork PRs without secret access), it falls back to local **MinIO** via `docker compose -f docker-compose-ci.yml up -d`.

The reusable `plugins.yml` workflow exposes all GitHub secrets as env vars (via `secrets-to-env-action`) before running `setup-unit.sh`, which is how the script reads the `HUAWEI_*` secrets.

### Project Structure

```
plugin-huawei/
├── src/main/java/io/kestra/plugin/huawei/
│   ├── AbstractConnection.java
│   ├── AbstractConnectionInterface.java
│   ├── ConnectionUtils.java
│   ├── TemporaryCredentialsConfig.java
│   ├── dms/
│   │   ├── kafka/
│   │   │   ├── AbstractDmsKafka.java
│   │   │   ├── Consume.java
│   │   │   ├── DmsKafkaConnectionInterface.java
│   │   │   ├── Produce.java
│   │   │   ├── RealtimeTrigger.java
│   │   │   ├── SaslMechanism.java
│   │   │   ├── SerdeType.java
│   │   │   ├── Trigger.java
│   │   │   ├── package-info.java
│   │   │   └── models/
│   │   │       └── Message.java
│   │   └── rocketmq/
│   │       ├── AbstractDmsRocketMq.java
│   │       ├── Consume.java
│   │       ├── DmsRocketMqConnectionInterface.java
│   │       ├── Publish.java
│   │       ├── RealtimeTrigger.java
│   │       ├── RocketMqSerdeType.java
│   │       ├── Trigger.java
│   │       ├── package-info.java
│   │       └── models/
│   │           └── Message.java
│   ├── dataarts/
│   │   ├── AbstractDataArts.java
│   │   ├── DataArtsConnectionInterface.java
│   │   ├── DataArtsService.java
│   │   ├── DataArtsUtils.java
│   │   ├── GetJobRun.java
│   │   ├── StartJobRun.java
│   │   ├── StopJobRun.java
│   │   ├── package-info.java
│   │   └── models/
│   │       └── JobRun.java
│   ├── functiongraph/
│   │   ├── AbstractFunctionGraph.java
│   │   ├── FunctionGraphConnectionInterface.java
│   │   ├── FunctionGraphInvokeException.java
│   │   ├── FunctionGraphUtils.java
│   │   ├── Invoke.java
│   │   └── package-info.java
│   ├── koocli/
│   │   ├── KooCLI.java
│   ├── ces/
│   │   ├── AbstractCes.java
│   │   ├── AbstractCesTrigger.java
│   │   ├── CesConnectionInterface.java
│   │   ├── CesUtils.java
│   │   ├── Dimension.java
│   │   ├── Push.java
│   │   ├── Query.java
│   │   ├── Trigger.java
│   │   └── package-info.java
│   ├── smn/
│   │   ├── AbstractSmn.java
│   │   ├── SmnConnectionInterface.java
│   │   ├── SmnUtils.java
│   │   ├── Publish.java
│   │   └── package-info.java
│   ├── dli/
│   │   ├── AbstractDli.java
│   │   ├── DliConnectionInterface.java
│   │   ├── DliService.java
│   │   ├── DliUtils.java
│   │   ├── Query.java
│   │   └── package-info.java
│   ├── eventgrid/
│   │   ├── AbstractEventGrid.java
│   │   ├── EventGridConnectionInterface.java
│   │   ├── EventGridUtils.java
│   │   ├── PutEvents.java
│   │   └── package-info.java
│   ├── dis/
│   │   ├── AbstractDis.java
│   │   ├── AbstractDisTrigger.java
│   │   ├── ConsumeOptionsInterface.java
│   │   ├── DisConnectionInterface.java
│   │   ├── DisService.java
│   │   ├── DisUtils.java
│   │   ├── DisWatermark.java
│   │   ├── SerdeType.java
│   │   ├── StartingPosition.java
│   │   ├── PutRecords.java
│   │   ├── Consume.java
│   │   ├── Trigger.java
│   │   ├── RealtimeTrigger.java
│   │   ├── package-info.java
│   │   └── models/
│   │       └── Record.java
│   ├── geminidb/
│   │   ├── AbstractGeminiDb.java
│   │   ├── PutItem.java
│   │   ├── GetItem.java
│   │   ├── DeleteItem.java
│   │   ├── Query.java
│   │   ├── Scan.java
│   │   └── package-info.java
│   ├── swr/
│   │   ├── AbstractSwr.java
│   │   ├── SwrConnectionInterface.java
│   │   ├── SwrUtils.java
│   │   ├── GetAuthToken.java
│   │   └── package-info.java
│   ├── iam/
│   │   ├── GetTemporaryCredentials.java
│   │   └── package-info.java
│   └── obs/
│       ├── AbstractObs.java
│       ├── AbstractObsInterface.java
│       ├── AbstractObsObject.java
│       ├── AbstractObsTrigger.java
│       ├── ActionInterface.java
│       ├── AuthType.java
│       ├── ListInterface.java
│       ├── ObsService.java
│       ├── ObsUtils.java
│       ├── package-info.java
│       ├── models/
│       │   └── ObsObject.java
│       ├── Copy.java
│       ├── CreateBucket.java
│       ├── Delete.java
│       ├── DeleteBucket.java
│       ├── DeleteList.java
│       ├── Download.java
│       ├── Downloads.java
│       ├── List.java
│       ├── Trigger.java
│       └── Upload.java
├── src/test/java/io/kestra/plugin/huawei/
│   ├── dataarts/
│   │   ├── DataArtsTasksTest.java
│   │   └── DataArtsUtilsTest.java
│   ├── dms/
│   │   ├── kafka/
│   │   │   ├── AbstractDmsKafkaTest.java
│   │   │   ├── ProduceConsumeTest.java
│   │   │   └── TriggerTest.java
│   │   └── rocketmq/
│   │       ├── AbstractDmsRocketMqTest.java
│   │       └── PublishConsumeTest.java
│   ├── functiongraph/
│   │   ├── FunctionGraphInvokeTest.java
│   │   └── FunctionGraphUtilsTest.java
│   ├── koocli/
│   │   └── KooCLITest.java
│   ├── ces/
│   │   ├── CesUtilsTest.java
│   │   ├── QueryPushTest.java
│   │   └── TriggerTest.java
│   ├── smn/
│   │   ├── SmnUtilsTest.java
│   │   └── PublishTest.java
│   ├── dli/
│   │   ├── DliUtilsTest.java
│   │   └── QueryTest.java
│   ├── eventgrid/
│   │   ├── EventGridUtilsTest.java
│   │   └── PutEventsTest.java
│   ├── dis/
│   │   ├── DisUtilsTest.java
│   │   ├── PutRecordsTest.java
│   │   ├── ConsumeTest.java
│   │   ├── TriggerTest.java
│   │   └── RealtimeTriggerTest.java
│   ├── geminidb/
│   │   ├── AbstractGeminiDbTest.java
│   │   ├── GeminiDbValidationTest.java
│   │   ├── PutItemTest.java
│   │   ├── GetItemTest.java
│   │   ├── DeleteItemTest.java
│   │   ├── QueryTest.java
│   │   └── ScanTest.java
│   ├── swr/
│   │   ├── SwrUtilsTest.java
│   │   └── GetAuthTokenTest.java
│   ├── iam/
│   │   ├── ConnectionUtilsExchangeTest.java
│   │   ├── TemporaryCredentialsConnectionTest.java
│   │   └── GetTemporaryCredentialsTest.java
│   └── obs/
│       ├── AbstractObsTest.java
│       ├── CopyTest.java
│       ├── CreateBucketTest.java
│       ├── DeleteBucketTest.java
│       ├── DeleteListTest.java
│       ├── DeleteTest.java
│       ├── DownloadTest.java
│       ├── DownloadsTest.java
│       ├── ListTest.java
│       ├── ObsUtilsTest.java
│       ├── TriggerTest.java
│       └── UploadTest.java
├── build.gradle
├── docker-compose.yml
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- OBS SDK: `com.huaweicloud:esdk-obs-java-bundle:3.25.5` (shaded, no BOM conflicts, slf4j excluded)
- `AuthTypeEnum.V2` is required for MinIO; real OBS uses `AuthTypeEnum.OBS` by default
- IAM SDK: `com.huaweicloud.sdk:huaweicloud-sdk-iam:3.1.152` (via `huaweicloud-sdk-bom` BOM); uses `GlobalCredentials` for AK/SK-based IAM calls and `IAMCredentials` (token in `X-Auth-Token` header) for the STS `createTemporaryAccessKeyByToken` call
- IAM tests use WireMock (`org.wiremock:wiremock-jetty12`) with `withEndpoint()` override to avoid network calls; production code uses `IamRegion.valueOf(region)` for endpoint resolution
- `TemporaryCredentialsConfig` uses field name `iamToken` (not `token`) to avoid clashing with the existing `securityToken` connection property; `GetTemporaryCredentials` keeps its public field named `token` for backward compatibility and maps it to `iamToken` when building the config
- DMS Kafka uses `org.apache.kafka:kafka-clients:3.9.0` (standard Kafka wire protocol; no Huawei SDK)
- DMS RocketMQ uses `org.apache.rocketmq:rocketmq-client:5.5.0`; AK/SK passed via `AclClientRPCHook`; logback/slf4j excluded. The former standalone `rocketmq-acl` module (`AclClientRPCHook`, `SessionCredentials`) was folded into `rocketmq-client` from 5.4.0 onward and is no longer published separately, so no `rocketmq-acl` dependency is declared
- DMS Kafka integration test gate: `DMS_KAFKA_TESTS=true`; DMS RocketMQ gate: `DMS_ROCKETMQ_TESTS=true`
- DataArts Studio SDK: `com.huaweicloud.sdk:huaweicloud-sdk-dataartsstudio` (version managed by `huaweicloud-sdk-bom`); used for the SDK core's `AKSKSigner` only — the DLF job-lifecycle V1 APIs are called via JDK `HttpClient` with signed headers
- DataArts Studio integration test gate: `DATAARTS_TESTS=true`; WireMock-based unit tests run unconditionally
- FunctionGraph SDK: `com.huaweicloud.sdk:huaweicloud-sdk-functiongraph` (version managed by `huaweicloud-sdk-bom`); uses `FunctionGraphClient` from the SDK; `FunctionGraphRegion.valueOf(region)` for known regions with fallback to `withEndpoint` for unknown ones
- FunctionGraph integration test gate: `FUNCTIONGRAPH_TESTS=true`; WireMock-based unit tests run unconditionally
- KooCLI uses `io.kestra:script` (already a compile dependency); no new SDK; default image `ubuntu:26.04` (glibc required, Alpine/musl unsupported). AK/SK, region, and security token are injected as env vars (`HUAWEICLOUD_SDK_AK`/`SK`/`REGION`/`SECURITY_TOKEN`); KooCLI reads none of them from the environment (only CLI flags or a saved profile), so a guarded `hcloud configure set --cli-profile=default` step writes a `default` profile referencing those env vars by shell name (e.g. `--cli-region="$HUAWEICLOUD_SDK_REGION"`) so secret values expand only inside the container, never on argv or in logs. No global output-format param; use `--cli-output=json|table|tsv` per command. Two guarded bootstrap steps prepend the user commands and auto-skip on prebuilt images: (1) `command -v curl || apt-get install ... curl ca-certificates tar`; (2) `command -v hcloud || curl ... | bash -s -- -y`. Each `hcloud` binary is partition-specific: it validates `--cli-region` against a baked-in catalog with no runtime override (not even `--cli-endpoint`), so the install URL is chosen by a 3-tier `resolveInstallScriptUrl(...)`: (1) explicit `installScriptUrl` (`Property<String>`, `null` by default) wins, for HCS/dedicated/future partitions; (2) a known sovereign region in `SOVEREIGN_INSTALL_URLS` (currently `eu-west-101`, its own `myhuaweicloud.eu` binary); (3) otherwise `INTERNATIONAL_INSTALL_URL` (`myhuaweicloud.com`), covering the full standard region catalog plus `eu-west-0`. Standard and EU-sovereign regions are both zero-config; `installScriptUrl` is only for HCS/dedicated/future partitions. `projectId`/`domainId` (inherited from `AbstractConnection`) are not used by KooCLI (documented as no-ops; no verified stable `configure set` flag for them). Integration test gate: `HUAWEI_CLI_TESTS=true`; unit tests run unconditionally
- CES SDK: `com.huaweicloud.sdk:huaweicloud-sdk-ces` (version managed by `huaweicloud-sdk-bom`); uses `CesClient` (v1) from the SDK; `CesRegion.valueOf(region)` for known regions with fallback to `withEndpoint` for unknown ones
- CES `showMetricData` requires at least one dimension (`dim.0` is `NON_NULL_NON_EMPTY` at the SDK level); `Query`/`Trigger` enforce 1–3 dimensions accordingly. `createMetricData` (`Push`) does not enforce this, so dimensions stay optional per metric there
- CES v1 APIs embed the project ID in the request path (`/V1.0/{project_id}/...`). The SDK auto-discovers the project only when resolving the endpoint from its region enum; a custom endpoint (`endpointOverride` or `endpointSuffix`, e.g. sovereign clouds like `myhuaweicloud.eu`) bypasses that and leaves `{project_id}` unsubstituted, yielding an opaque gateway 400 (`ces.0047 "URI incorrect."` / APIGW auth errors). `AbstractCes.client()` therefore fails fast requiring `projectId` whenever a custom endpoint is set — applies to `Push`, `Query`, and `Trigger` (which runs through `Query`)
- CES `Push` always sends `ttl` (default 172800s / 2 days) and `collect_time` (default now); CES marks both mandatory on `createMetricData`, so they are never omitted even when the flow leaves them unset
- CES integration test gate: `CES_TESTS=true`; WireMock-based unit tests run unconditionally
- SMN SDK: `com.huaweicloud.sdk:huaweicloud-sdk-smn` (version managed by `huaweicloud-sdk-bom`); the publish API lives in `com.huaweicloud.sdk.smn.v2` (`SmnClient.publishMessage`); `SmnRegion.valueOf(region)` for known regions with fallback to `withEndpoint` for unknown ones, using the CES-style suffix-first ordering so an explicit `endpointSuffix` always wins for sovereign clouds
- SMN v2 APIs embed the project ID in the request path (`/v2/{project_id}/notifications/topics/{topic_urn}/publish`), same failure mode as CES: a custom endpoint (`endpointOverride`/`endpointSuffix`) bypasses the SDK's automatic project discovery, so `AbstractSmn.client()` fails fast requiring `projectId` whenever a custom endpoint is set
- SMN topics are addressed by URN (`urn:smn:<region>:<project_id>:<topic_name>`), not an ARN; `Publish` sends exactly one message per call (no AWS-style multi-message `from` batching)
- `Publish` requires exactly one of `message`, `messageStructure`, or `messageTemplateName` — fails fast naming all three when zero or more than one is set. `tags` is only valid together with `messageTemplateName`
- SMN's `MessageAttribute.TypeEnum` also defines `STRING_ARRAY` and `PROTOCOL`, but `Publish.MessageAttributeType` only exposes `STRING` — the common case; extending to array/protocol semantics is deferred until a concrete need arises
- SMN integration test gate: `SMN_TESTS=true` (real SMN topic; requires `SMN_TOPIC_URN` and `SMN_REGION` env vars); WireMock-based unit tests run unconditionally
- DLI SDK: `com.huaweicloud.sdk:huaweicloud-sdk-dli` (version managed by `huaweicloud-sdk-bom`); the SQL-job API lives in `com.huaweicloud.sdk.dli.v1` (not `v3`, despite DLI's own API docs calling it a "v3-generation" client); `DliRegion.valueOf(region)` for known regions with fallback to `withEndpoint` for unknown ones, using the CES-style suffix-first ordering (not FunctionGraph's enum-first ordering) so an explicit `endpointSuffix` always wins for sovereign clouds
- DLI v1 APIs embed the project ID in the request path (`/v1.0/{project_id}/...`), same failure mode as CES: a custom endpoint (`endpointOverride`/`endpointSuffix`) bypasses the SDK's automatic project discovery, so `AbstractDli.client()` fails fast requiring `projectId` whenever a custom endpoint is set
- DLI `Query`'s `STORE` fetch type opens a second SDK client (`ObsClient`, via `ObsService.buildClient(...)`) against a different host than the DLI endpoint; it inherits AK/SK/region/`endpointSuffix` from the same connection config but never reuses the DLI `endpointOverride` — dedicated `obsEndpointOverride`/`obsPathStyleAccess`/`obsAuthType` properties target the OBS read-back explicitly
- DLI's `previewSqlJobResult` (used by `fetchType=FETCH`/`FETCH_ONE`) is hard-capped at 1000 rows and does not paginate; `STORE` (export-result job to OBS, then read back) is the only way to retrieve a full result set
- DLI `Query` overrides `kill()` to call `cancelSqlJob` on whichever job (query or export) is currently in flight, so a killed execution doesn't leave a DLI queue job running/billing in the background
- DLI integration test gate: `DLI_TESTS=true` (real DLI + OBS, or MinIO for the OBS read-back leg via `obsEndpointOverride`/`obsPathStyleAccess=true`/`obsAuthType=V2`); WireMock-based unit tests run unconditionally
- DLI's shared `default` queue rejects `previewSqlJobResult` outright (`Do not support use default queue to getJobResult`) — a permanent Huawei DLI limitation, confirmed against a live account. `Query` therefore fails fast with `IllegalArgumentException` before submitting the job when `fetchType` is `FETCH`/`FETCH_ONE` and the resolved queue is the `default` queue (an omitted `queue` also resolves to `default`). `STORE` (export-result to OBS) and `NONE` both work on the `default` queue and are unaffected
- EventGrid SDK: `com.huaweicloud.sdk:huaweicloud-sdk-eg` (version managed by `huaweicloud-sdk-bom`); the `putEvents` API lives in `com.huaweicloud.sdk.eg.v1` (`EgClient`, `com.huaweicloud.sdk.eg.v1.model`); `EgRegion.valueOf(region)` for known regions with fallback to `withEndpoint` for unknown ones, using the CES-style suffix-first ordering so an explicit `endpointSuffix` always wins for sovereign clouds
- EventGrid v1 APIs embed the project ID in the request path (`/v1/{project_id}/...`), same failure mode as CES: a custom endpoint (`endpointOverride`/`endpointSuffix`) bypasses the SDK's automatic project discovery, so `AbstractEventGrid.client()` fails fast requiring `projectId` whenever a custom endpoint is set
- ⚠️ EventGrid's EU/sovereign host is unverified: the SDK's `EgRegion` enum has no EU entry (so an unknown region always falls back to suffix-derivation, `eg.<region>.myhuaweicloud.com`), while Huawei's own EU documentation shows a different host (`events.eu-west-101.myhuaweicloud.eu`). Use `endpointOverride`/`endpointSuffix` as the escape hatch until this is verified against a live EU-sovereign account
- `PutEvents` has no per-request batch cap enforced client-side — EventGrid's documented limit (if any) is unknown, so the task always sends the full `events` list in a single `putEvents` call and surfaces any size-related API error verbatim rather than guessing a safe chunk size
- EventGrid integration test gate: `EVENTGRID_TESTS=true`; WireMock-based unit tests run unconditionally
- DIS SDK: `com.huaweicloud.sdk:huaweicloud-sdk-dis` (version managed by `huaweicloud-sdk-bom`); the stream API lives in `com.huaweicloud.sdk.dis.v2` (`DisClient`); `DisRegion.valueOf(region)` for known regions with fallback to `withEndpoint` for unknown ones, using the CES-style suffix-first ordering so an explicit `endpointSuffix` always wins for sovereign clouds
- DIS v2 APIs embed the project ID in the request path (`/v2/{project_id}/...`), same failure mode as CES/SMN/DLI: a custom endpoint (`endpointOverride`/`endpointSuffix`) bypasses the SDK's automatic project discovery, so `AbstractDis.client()`/`AbstractDisTrigger.client()` (both delegating to `DisService.buildClient(...)`) fail fast requiring `projectId` whenever a custom endpoint is set
- DIS has no dedicated `putRecords`/`getRecords`/`getPartitionCursor` methods on `DisClient` despite the model classes (`PutRecordsRequest`, etc.) being named that way: writes go through `sendRecords` (wrapping `PutRecordsRequest`/`PutRecordsResultEntry`), partition-cursor resolution through `showCursor` (`ShowCursorRequest.CursorTypeEnum`: `TRIM_HORIZON`/`LATEST`/`AT_TIMESTAMP`/`AT_SEQUENCE_NUMBER`/`AFTER_SEQUENCE_NUMBER`), reads through `consumeRecords`, and partition enumeration through `showStream` (paginated via `hasMorePartitions`/`startPartitionId`)
- `PutRecords` can receive an HTTP 200 from `sendRecords` while still rejecting individual records (`failedRecordCount` > 0 in the body) — `failOnUnsuccessfulRecords` (default `true`) fails the task in that case; per-record results (including `errorCode`/`errorMessage`) are written to ION at `uri` rather than returned inline, since a batch can be arbitrarily large
- `Trigger` and `RealtimeTrigger` deliberately avoid DIS's server-side app/checkpoint API (`CreateApp`/`CommitCheckpoint`/`ShowCheckpoint`/`ShowConsumerState`) in favor of client-side partition cursors plus a watermark (last delivered sequence number per partition) persisted in the flow's namespace KV Store, mirroring `ces.Trigger`'s watermark approach; the KV key is length-prefixed (`dis_trigger_watermark_<flowId.length()>_<flowId>_<triggerId>`) to avoid `flowId`/`triggerId` concatenation collisions
- DIS integration test gate: `DIS_TESTS=true` (real DIS stream; requires `DIS_STREAM_NAME` and `DIS_REGION` env vars); WireMock-based unit tests run unconditionally
- GeminiDB for NoSQL ships a DynamoDB-Compatible API (wire-compatible with Amazon DynamoDB over HTTPS with SigV4 AK/SK signing — Huawei's own docs connect via boto3 with an explicit `endpoint_url`), so the plugin uses the **AWS SDK v2** (`software.amazon.awssdk:dynamodb` + `url-connection-client` for the sync HTTP transport) as the wire-compatible client, not any Huawei-branded SDK. The `huaweicloud-sdk-nosql` SDK is control-plane only (instance CRUD) and has no item-level data-plane API, so it is not used at all
- Both `software.amazon.awssdk:dynamodb` and `url-connection-client` are declared **without an explicit version**: the `io.kestra:platform` BOM (already `enforcedPlatform`'d in this project) imports `software.amazon.awssdk:bom`, so the AWS SDK version is aligned automatically — same mechanism `plugin-aws` relies on
- GeminiDB instances are addressed by a **per-instance connection address** (`endpoint`, mandatory `Property<String>`), never a region-derived host — this is the one Huawei service in this plugin where `region` has no bearing on routing. `region` is still required by the SDK's SigV4 signer and defaults to a placeholder (`cn-north-1`) purely for signing; document it as such wherever it's exposed
- `AbstractGeminiDb.objectFrom(Object)` falls back to `toString()` for any type it doesn't special-case (notably numbers), so numeric attributes are written as DynamoDB `S` (string), not `N` — an inherited limitation from the upstream `io.kestra.plugin.aws.dynamodb.AbstractDynamoDb` this class is ported from, not a new gap
- `Query`/`Scan` read a single response page and do **not** paginate across `LastEvaluatedKey`; `warnIfTruncated` logs an INFO message whenever the response is truncated so large result sets aren't silently cut short — bounded `limit` (1-1000, default 100, enforced at render time via `renderedLimit()` rather than `@Min`/`@Max` — `Property<>` has no Hibernate ValueExtractor) caps how much is read per page
- GeminiDB test gate: `GEMINIDB_TESTS=true` is reserved for a future live-cloud-only test, but none exists yet — the default-path tests run **unconditionally** against `amazon/dynamodb-local` (started via `docker-compose-ci.yml`, unconditionally in `.github/setup-unit.sh`, no credential-based branching like the OBS/MinIO fallback) since DynamoDB Local speaks the identical wire protocol GeminiDB exposes
- SWR SDK: `com.huaweicloud.sdk:huaweicloud-sdk-swr` (version managed by `huaweicloud-sdk-bom`); the registry-credential API lives in `com.huaweicloud.sdk.swr.v2` (`SwrClient.createSecret`); `SwrRegion.valueOf(region)` for known regions with fallback to `withEndpoint` for unknown ones, using the CES-style suffix-first ordering so an explicit `endpointSuffix` always wins for sovereign clouds
- ⚠️ **`GetAuthToken` has no `durationSeconds` property**, unlike AWS ECR's `GetAuthToken`: inspecting the actual `huaweicloud-sdk-swr` request model (`CreateSecretRequest`, verified via `javap` against the resolved 3.1.206 jar) shows it exposes only a `projectname` field — no duration/TTL parameter exists on SWR's `createSecret` API for the client to set. The issued credential's validity window is entirely SWR-controlled; `expiry` in the output is SWR's `X-Swr-Expireat` header (ISO-8601 UTC, e.g. `2026-07-23T08:46:07.811000Z`, verified against a live account) parsed to an `Instant` — an absent/unparseable value logs a warning and yields `null` rather than failing the task. Do not add a `durationSeconds` property without first re-verifying against a newer SDK version that the field actually exists
- **No `projectId` fail-fast on a custom endpoint** — unlike CES/SMN/DLI/EventGrid/DIS, SWR's `createSecret` path (`/v2/manage/utils/secret`) has no `{project_id}` segment (confirmed via the SDK's embedded `HttpRequestDef` metadata), so `AbstractSwr.client()` deliberately omits the `requireProjectIdForCustomEndpoint` guard those other services carry. Do not copy that guard into `AbstractSwr`/`SwrUtils`
- `GetAuthToken` decodes `auths.<registry-host>.auth` (base64 `username:password`, per-registry map keyed by host) in preference to parsing the human-readable `X-Swr-Dockerlogin` response header — the map is structured data and only falls back to header-parsing when `auths` comes back empty. `password` is wrapped in `EncryptedString`, mirroring how AWS ECR's `GetAuthToken` encrypts its token
- SWR unit tests are WireMock-based and run unconditionally; no live-cloud integration test gate exists yet for SWR

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
