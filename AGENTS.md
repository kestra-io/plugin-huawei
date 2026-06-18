# Kestra Huawei Plugin

## What

Provides Kestra plugin tasks and shared abstractions for Huawei Cloud services under `io.kestra.plugin.huawei`.

## Why

Teams using Huawei Cloud need first-class Kestra integrations for storage, authentication, and future services without hand-rolling HTTP calls or managing SDK lifecycle themselves.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.huawei`:

- `io.kestra.plugin.huawei` — plugin-wide abstractions (`AbstractConnection`, `AbstractConnectionInterface`, `ConnectionUtils`)
- `io.kestra.plugin.huawei.iam.tasks` — IAM authentication tasks (`GetToken`)
- `io.kestra.plugin.huawei.obs` — OBS shared layer (`AbstractObs`, `AbstractObsObject`, `AbstractObsInterface`, `AuthType`, `ListInterface`, `ObsUtils`, `ObsService`)
- `io.kestra.plugin.huawei.obs.tasks` — OBS object tasks (`Upload`, `Download`, `List`, `Copy`, `Delete`, `DeleteList`, `CreateBucket`, `DeleteBucket`, `Downloads`, `Trigger`)
- `io.kestra.plugin.huawei.obs.models` — serializable output models (`ObsObject`)

Infrastructure dependencies (Docker Compose services):

- `app` (`docker-compose.yml`) — Kestra application for manual plugin testing
- `minio` (`docker-compose-ci.yml`) — S3-compatible object storage for integration tests (ports 9000/9001, credentials minioadmin/minioadmin); started in CI by `.github/setup-unit.sh`, kept out of the production `docker-compose.yml`

### Key Plugin Classes

- `io.kestra.plugin.huawei.ConnectionUtils` — Static factory for Huawei Cloud SDK credentials (`projectCredentials`, `globalCredentials`) and clients (`iamClient`, `iamClientWithToken`); central place for credential wiring across all plugin tasks
- `io.kestra.plugin.huawei.iam.tasks.GetToken` — Exchanges an existing IAM token for short-lived STS credentials via `POST /v3.0/OS-CREDENTIAL/securitytokens`; outputs `accessKeyId`, `secretAccessKey`, `securityToken`, and `expirationTime` for use in downstream tasks
- `io.kestra.plugin.huawei.obs.tasks.Upload` — Uploads a file from Kestra internal storage to OBS
- `io.kestra.plugin.huawei.obs.tasks.Download` — Downloads an OBS object into Kestra internal storage
- `io.kestra.plugin.huawei.obs.tasks.List` — Lists OBS objects with prefix/regexp filtering, full pagination
- `io.kestra.plugin.huawei.obs.tasks.Copy` — Server-side copy of an OBS object within or between buckets; `delete=true` for move semantics
- `io.kestra.plugin.huawei.obs.tasks.Delete` — Deletes a single OBS object by bucket/key (and optional versionId)
- `io.kestra.plugin.huawei.obs.tasks.DeleteList` — Batch-deletes all objects matching a prefix/regexp filter in chunks of 1000
- `io.kestra.plugin.huawei.obs.tasks.CreateBucket` — Creates an OBS bucket; idempotent if the caller already owns it
- `io.kestra.plugin.huawei.obs.tasks.DeleteBucket` — Deletes an empty OBS bucket; idempotent by default (`errorOnMissing=false`) if the bucket is absent
- `io.kestra.plugin.huawei.obs.tasks.Downloads` — Lists matching objects, downloads each to Kestra storage, applies NONE/DELETE/MOVE action
- `io.kestra.plugin.huawei.obs.tasks.Trigger` — Polling trigger that fires when new objects appear in a bucket; applies action after download to avoid re-triggering

### Shared OBS Layer

- `AbstractObs extends AbstractConnection implements AbstractObsInterface` — holds `endpointOverride`, `pathStyleAccess`, `authType`; exposes `protected ObsClient client(RunContext)` factory
- `AbstractObsObject extends AbstractObs` — adds `bucket` property shared by all single-object tasks
- `ObsUtils` — static endpoint resolution (override → region-derived → throws)
- `ObsService` — static helpers: `buildClient(...)` (shared client factory for tasks and Trigger), `download(...)` (buffers via temp file → internal storage), and `list(...)` (paginated, optional regexp filter)
- `AuthType` enum — `OBS` / `V2` / `V4` wrapping SDK `AuthTypeEnum`; use `V2` for MinIO/S3-compatible endpoints
- `ListInterface` — shared property schema (`prefix`, `delimiter`, `marker`, `maxKeys`, `regexp`) implemented by List, DeleteList, Downloads, and Trigger
- `ActionInterface` — shared post-download action schema (`action` enum: NONE/DELETE/MOVE; `moveTo.bucket`, `moveTo.keyPrefix`) implemented by Downloads and Trigger

### Integration Tests

Integration tests require MinIO. Start it with:

```bash
docker compose -f docker-compose-ci.yml up -d
```

Then run tests with:

```bash
OBS_MINIO_TESTS=true ./gradlew test
```

Tests are guarded by `@EnabledIfEnvironmentVariable(named = "OBS_MINIO_TESTS", matches = "true")` so they are skipped unless the variable is set. To run against a live Huawei Cloud OBS endpoint instead of MinIO, set `OBS_TEST_ENDPOINT` (plus `OBS_TEST_ACCESS_KEY`, `OBS_TEST_SECRET_KEY`, `OBS_TEST_AUTH_TYPE`, `OBS_TEST_PATH_STYLE`, and optionally `OBS_TEST_BUCKET` to reuse a pre-created bucket).

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
│   ├── iam/
│   │   └── tasks/
│   │       ├── GetToken.java
│   │       └── package-info.java
│   └── obs/
│       ├── AbstractObs.java
│       ├── AbstractObsInterface.java
│       ├── AbstractObsObject.java
│       ├── ActionInterface.java
│       ├── AuthType.java
│       ├── ListInterface.java
│       ├── ObsService.java
│       ├── ObsUtils.java
│       ├── package-info.java
│       ├── models/
│       │   └── ObsObject.java
│       └── tasks/
│           ├── Copy.java
│           ├── CreateBucket.java
│           ├── Delete.java
│           ├── DeleteBucket.java
│           ├── DeleteList.java
│           ├── Download.java
│           ├── Downloads.java
│           ├── List.java
│           ├── Trigger.java
│           ├── Upload.java
│           └── package-info.java
├── src/test/java/io/kestra/plugin/huawei/
│   ├── iam/
│   │   └── tasks/
│   │       └── GetTokenTest.java
│   └── obs/
│       ├── AbstractMinioTest.java
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

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
