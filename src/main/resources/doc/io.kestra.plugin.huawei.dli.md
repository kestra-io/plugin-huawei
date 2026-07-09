# How to use the DLI plugin

Runs SQL queries against [Huawei Cloud DLI (Data Lake Insight)](https://www.huaweicloud.com/product/dli.html), the Huawei equivalent of AWS Athena, querying data in OBS or federated sources via a serverless Spark SQL engine.

## Authentication

DLI tasks authenticate using AK/SK request signing (Huawei Cloud HMAC-SHA256). Provide `accessKeyId` and `secretAccessKey` via [Kestra secrets](https://kestra.io/docs/concepts/secret). When working with temporary credentials obtained from IAM, also supply `securityToken`, or configure `temporaryCredentials` for inline IAM credential exchange.

Secret properties: `accessKeyId`, `secretAccessKey`, `securityToken`.

Configure shared defaults via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.dli
    values:
      region: eu-west-101
      projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
```

### `endpointSuffix`

Controls the top-level domain used when deriving the DLI endpoint from `region`. Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the Huawei Cloud European sovereign cloud.

### `endpointOverride`

Overrides the default endpoint derived from `region` and `endpointSuffix`. Use for private endpoints, non-standard deployments, or local tests. When set, `endpointSuffix` is ignored.

## Gotchas

- **`projectId` is required with a custom endpoint**: DLI's v1 APIs embed the project ID in the request path (`/v1.0/{project_id}/...`). The SDK auto-discovers the project only when resolving the endpoint from its region enum; a custom endpoint (`endpointOverride` or `endpointSuffix`) bypasses that, so `Query` fails fast requiring `projectId` whenever a custom endpoint is set.
- **`FETCH`/`FETCH_ONE` are capped at 1000 rows**: DLI's result-preview API (`previewSqlJobResult`) is hard-capped and does not paginate. Use `fetchType: STORE` to retrieve a full result set.
- **`STORE` uses a second host for the read-back**: the exported result is read back from OBS via a separate `ObsClient`, inherited from the same AK/SK/region/`endpointSuffix` as the DLI connection, but never from the DLI `endpointOverride`. Use the dedicated `obsEndpointOverride`, `obsPathStyleAccess`, and `obsAuthType` properties (e.g. for MinIO in tests) to target the OBS read-back independently.
- **Non-`QUERY` jobs never return rows**: DDL/DML statements (`DDL`, `INSERT`, `DCL`, …) skip the fetch step regardless of `fetchType` — only `jobId`, `jobType`, and `status` are populated.
- **The DLI job is not auto-cancelled on timeout**: if `maxDuration` is exceeded before the job reaches a terminal state, the task throws but leaves the job running on DLI. `Query` does cancel the in-flight job on a Kestra execution `kill()`.

## Tasks

### Query

Submits a SQL statement as a DLI SQL job, waits for it to reach a terminal state (`FINISHED`, `FAILED`, or `CANCELLED`), and returns the result according to `fetchType`.

Required: `sql`.

Optional: `database` (maps to DLI's `currentdb`), `queue` (DLI queue name), `fetchType` (`STORE`, `FETCH`, `FETCH_ONE`, `NONE`; defaults to `STORE`), `outputLocation` (an `obs://bucket/prefix` URI; required when `fetchType` is `STORE`), `conf` (list of `key=value` Spark/DLI configuration entries), `tags`, `maxDuration` (default 1 hour), `interval` (default 5 s).

`fetchType` behavior:

- `STORE` (default): submits a DLI export-result job that writes the full result set to `outputLocation` on OBS as newline-delimited JSON under a job-scoped subpath, waits for the export to finish, downloads the exported objects, and re-serializes all rows as ION into Kestra internal storage.
- `FETCH`: reads all rows (up to 1000) directly via `previewSqlJobResult`.
- `FETCH_ONE`: reads only the first row via the same preview API.
- `NONE`: returns as soon as the job completes, without fetching a result set. Use this for DDL/DML statements.

Outputs: `jobId`, `jobType` (e.g. `QUERY`, `DDL`, `DCL`, `INSERT`), `status` (`FINISHED`, `FAILED`, `CANCELLED`), `rows` (populated only for `FETCH`), `row` (populated only for `FETCH_ONE`), `uri` (ION file URI, populated only for `STORE`), `size` (number of rows fetched or stored).
