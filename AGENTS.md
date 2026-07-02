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
- `io.kestra.plugin.huawei.ces` — CES (Cloud Eye Service) tasks/trigger (`AbstractCes`, `CesConnectionInterface`, `CesUtils`, `Dimension`, `Push`, `Query`, `Trigger`)

Infrastructure dependencies (Docker Compose services):

- `app` (`docker-compose.yml`) — Kestra application for manual plugin testing
- `minio` (`docker-compose-ci.yml`) — S3-compatible object storage for integration tests (ports 9000/9001, credentials minioadmin/minioadmin); started in CI by `.github/setup-unit.sh`, kept out of the production `docker-compose.yml`
- `kafka` (`docker-compose-ci.yml`) — KRaft-mode Kafka broker for DMS Kafka integration tests (port 9092, no auth)
- `rocketmq-namesrv` / `rocketmq-broker` (`docker-compose-ci.yml`) — Apache RocketMQ 4.9.8 for DMS RocketMQ integration tests (namesrv port 9876, broker port 10911)

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

**CES (Cloud Eye Service)**

- `io.kestra.plugin.huawei.ces.Push` — Pushes custom metric datapoints via `createMetricData`; namespace must NOT use the reserved `SYS.` prefix; chunks batches over 10 datapoints (CES per-request cap)
- `io.kestra.plugin.huawei.ces.Query` — Queries metric statistics via `showMetricData`; requires 1 to 3 dimensions (CES mandates at least one, `dim.0`, to identify the resource instance); `window` (default `PT1H`) is converted to `from`/`to` epoch-millisecond bounds ending now; returns `series` sorted by timestamp ascending, capped at `Query.MAX_SERIES_SIZE` (1440) most-recent datapoints to bound memory when `period=RAW`
- `io.kestra.plugin.huawei.ces.Trigger` — Polling trigger delegating to `Query`; fires when at least one *new* datapoint is found (optionally matching `threshold`/`comparisonOperator`, default `GREATER_THAN`); persists a watermark (last-seen datapoint timestamp) in the flow's namespace KV Store (`runContext.namespaceKv(namespace)`, key `ces_trigger_watermark_<flowId>_<triggerId>`) so overlapping `window`/`interval` combinations never re-fire on the same datapoint
- `io.kestra.plugin.huawei.ces.AbstractCes` — Base class extending `AbstractConnection`; builds `CesClient` using `CesRegion.valueOf(region)` (with fallback to `withEndpoint` for unknown regions) or direct `endpointOverride`; validates AK/SK completeness
- `io.kestra.plugin.huawei.ces.CesUtils` — Static endpoint resolution (`endpointOverride` → region+suffix-derived → throws) plus `service.item` namespace format validation (`validateNamespaceFormat`, `validateCustomNamespace`)
- `io.kestra.plugin.huawei.ces.Dimension` — Shared `name`/`value` pair used by both `Push` (per-metric dimensions) and `Query`/`Trigger` (resource-identifying dimensions), mapped to CES's `dim.0`/`dim.1`/`dim.2` query parameters as `name,value` strings

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

All tests are guarded by `@EnabledIfEnvironmentVariable` so they are skipped unless the matching gate variable is set.

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
│   ├── ces/
│   │   ├── AbstractCes.java
│   │   ├── CesConnectionInterface.java
│   │   ├── CesUtils.java
│   │   ├── Dimension.java
│   │   ├── Push.java
│   │   ├── Query.java
│   │   ├── Trigger.java
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
│   ├── ces/
│   │   ├── CesUtilsTest.java
│   │   ├── QueryPushTest.java
│   │   └── TriggerTest.java
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
- DMS RocketMQ uses `org.apache.rocketmq:rocketmq-client:4.9.8` + `rocketmq-acl:4.9.8`; AK/SK passed via `AclClientRPCHook`; logback/slf4j excluded
- DMS Kafka integration test gate: `DMS_KAFKA_TESTS=true`; DMS RocketMQ gate: `DMS_ROCKETMQ_TESTS=true`
- DataArts Studio SDK: `com.huaweicloud.sdk:huaweicloud-sdk-dataartsstudio` (version managed by `huaweicloud-sdk-bom`); used for the SDK core's `AKSKSigner` only — the DLF job-lifecycle V1 APIs are called via JDK `HttpClient` with signed headers
- DataArts Studio integration test gate: `DATAARTS_TESTS=true`; WireMock-based unit tests run unconditionally
- FunctionGraph SDK: `com.huaweicloud.sdk:huaweicloud-sdk-functiongraph` (version managed by `huaweicloud-sdk-bom`); uses `FunctionGraphClient` from the SDK; `FunctionGraphRegion.valueOf(region)` for known regions with fallback to `withEndpoint` for unknown ones
- FunctionGraph integration test gate: `FUNCTIONGRAPH_TESTS=true`; WireMock-based unit tests run unconditionally
- CES SDK: `com.huaweicloud.sdk:huaweicloud-sdk-ces` (version managed by `huaweicloud-sdk-bom`); uses `CesClient` (v1) from the SDK; `CesRegion.valueOf(region)` for known regions with fallback to `withEndpoint` for unknown ones
- CES `showMetricData` requires at least one dimension (`dim.0` is `NON_NULL_NON_EMPTY` at the SDK level); `Query`/`Trigger` enforce 1–3 dimensions accordingly. `createMetricData` (`Push`) does not enforce this, so dimensions stay optional per metric there
- CES integration test gate: `CES_TESTS=true`; WireMock-based unit tests run unconditionally

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
