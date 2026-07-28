# How to use the DataArts Studio plugin

Manages batch job runs in [Huawei Cloud DataArts Studio](https://www.huaweicloud.com/product/dataarts.html) (DataArts Factory / DLF) via the DataArts Factory V2 REST API (`/v2/{project_id}/factory/...`).

## Authentication

DataArts Studio tasks authenticate using AK/SK request signing (Huawei Cloud HMAC-SHA256). Provide `accessKeyId` and `secretAccessKey` via [Kestra secrets](https://kestra.io/docs/concepts/secret). When working with temporary credentials, also supply `securityToken`.

All tasks require `projectId` — the Huawei Cloud project ID of the region where the DataArts workspace is deployed.

Secret properties: `accessKeyId`, `secretAccessKey`, `securityToken`.

Configure shared defaults via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.dataarts
    values:
      region: eu-west-101
      projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
      workspaceId: "{{ secret('HUAWEI_WORKSPACE_ID') }}"
```

### `workspaceId`

Required only when your account has multiple DataArts Studio workspaces. When omitted, the default workspace is used. Find the workspace ID in the DataArts Studio console under **Workspaces → Settings**.

### `endpointOverride`

Overrides the default endpoint derived from `region` (`https://dayu.<region>.myhuaweicloud.com`). Use for private endpoints, non-standard deployments (e.g. non-`.com` sovereign partitions, which have no region-derived host), or local tests.

## Tasks

### StartJobRun

Triggers an on-demand run of a DataArts Factory job and optionally waits for it to complete.

The task calls the DataArts Factory `run-immediate` API ("Executing a Job Immediately"), which starts a single on-demand run — not the `start` API, which merely toggles a job's schedule and rejects run-once jobs with `DLF.3051`. The response carries no usable instance ID, so the task immediately queries the instance list to resolve the newly created run and returns the first result ordered by `planTime`/`startTime` descending.

Required: `jobName`, `projectId`.

Optional: `jobParams` (map of runtime parameters), `wait` (default `true`), `maxDuration` (default 1 hour), `interval` (default 5 s).

When `wait: true`, the task polls until the run reaches a terminal state (`success`, `fail`, `running-exception`, `manual-stop`) and fails the Kestra task unless the status is `success`.

Outputs: `jobName`, `instanceId`, `status`, `planTime`, `startTime`, `endTime`, `lastUpdateTime`, `errorMessage`.

**Gotcha**: the DataArts Factory run-immediate API is asynchronous — the new instance may not appear in the list immediately. `StartJobRun` retries the instance list (spaced by `interval`) until the new instance appears or `maxDuration` is exhausted, then fails with a clear error. Increase `maxDuration` if jobs take longer than the default 1 hour to appear.

**Region limitation**: on some sovereign gateways — verified on `tr-west-1` (T-Systems EU) — the `run-immediate` route rejects every request with `DLF.3051 "The request parameter is invalid."`, regardless of the job's schedule type or the request body. The route is absent from the documented V2 API family there, the deprecated `/v1/` job APIs return `APIGW.0101`, and the console's own trigger call is session-authenticated (not exposed on the public AK/SK gateway). `GetJobRun`/`StopJobRun`'s read operations still work, but **`StartJobRun` is not usable on those regions**. Standard (non-sovereign) regions are expected to accept the call.

### GetJobRun

Fetches the current status of a DataArts Factory job run without polling.

Required: `jobName`, `projectId`.

Optional: `instanceId` — when omitted, the most recently started instance for the job is returned.

Outputs: `jobName`, `instanceId`, `status`, `planTime`, `startTime`, `endTime`, `lastUpdateTime`, `errorMessage`.

### StopJobRun

Stops an in-progress DataArts Factory job run instance.

Required: `jobName`, `instanceId`, `projectId`.

Optional: `wait` (default `true`), `maxDuration` (default 10 minutes), `interval` (default 3 s).

When `wait: true`, polls until the instance confirms `manual-stop` or another terminal state.

Outputs: `jobName`, `instanceId`, `status`, plus timing and error fields.

## Job run statuses

| Status | Description |
|---|---|
| `waiting` | Queued, not yet started |
| `running` | Currently executing |
| `success` | Completed successfully |
| `fail` | Completed with an error |
| `running-exception` | Running but an exception was detected |
| `pause` | Paused by user |
| `manual-stop` | Stopped manually |

Terminal states (no further transitions): `success`, `fail`, `running-exception`, `manual-stop`.
