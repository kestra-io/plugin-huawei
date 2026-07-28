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

The task creates a **supplement-data (PatchData) instance** via `POST /v2/{project_id}/factory/supplement-data`, then polls `GET /v2/{project_id}/factory/supplement-data` for its status.

**Why supplement-data**: DataArts Factory publishes no usable job-trigger API. Both `jobs/{job_name}/run-immediate` ("Executing a Job Immediately") and `jobs/{job_name}/start` ("Starting a Job") reject every request with `DLF.3051 "The request parameter is invalid."` — verified live on both `tr-west-1` (T-Systems EU sovereign) and `ap-southeast-3` (standard `.com`, Singapore), on run-once and scheduled jobs alike, so the failure is not partition-specific. Neither route appears in the Huawei SDK's own `HttpRequestDef` route metadata at any API version, the deprecated `/v1/` job APIs return `APIGW.0101`, and the console's own Execute button uses a session-authenticated route that is not exposed on the public AK/SK gateway. Supplement-data is the only declared mechanism that creates a run, and it comes with matching status and stop routes.

**Requirement — the job must have a trigger.** Supplement-data only accepts a job configured with a cron schedule, an HTTP trigger, or a parent job; a run-once / manually-triggered job is rejected with `DLF.30111 "The job [<name>] should be cron job or http trigger or parent trigger."`. The schedule does not need to fire on its own — it only has to exist. Because DataArts publishes no other working job-trigger route, giving the job a schedule in the console is a prerequisite for driving it from Kestra at all.

**What that means in practice**: supplement-data is a *backfill* — it runs the job over a range of business dates. Consequences worth knowing:

- The run itself appears in the console's **Supplement Data** view; the job instances it spawns appear under **Monitor Instance**, named `P_<jobName>_<timestamp>`.
- It is identified by the `runName` you choose (or one generated for you), not by a numeric instance ID.
- There are no `jobParams`: the supplement-data API takes no runtime parameters. Parameterise the job by business date instead.

Required: `jobName`, `projectId`.

Optional: `runName` (defaults to a generated `kestra_<jobName>_<random>`), `startDate` (defaults to the start of today, UTC), `endDate` (defaults to the end of `startDate`'s day), `parallel` (default 1), `dayGranularity` (default `true`), `stopWhenFail` (default `true`), `runTimeWindow` (omitted by default), `wait` (default `true`), `maxDuration` (default 1 hour), `interval` (default 5 s).

### Running a job once: set the range to one scheduling period

**A backfill creates one instance per scheduling period of the job that falls inside the range**, and at `parallel: 1` those instances run one after another. So the range is what controls how much work a single `StartJobRun` does — the *default* range is a whole day, which for an hourly job means 24 sequential instances and minutes of wall time even though each instance finishes in seconds.

To run the job just once, cover a single scheduling period — one hour for an hourly job:

```yaml
  - id: run_once
    type: io.kestra.plugin.huawei.dataarts.StartJobRun
    accessKeyId: "{{ secret('HUAWEI_AK') }}"
    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
    region: ap-southeast-3
    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
    workspaceId: "{{ secret('HUAWEI_WORKSPACE_ID') }}"
    jobName: my_hourly_job
    startDate: "{{ now() | date('yyyy-MM-dd HH:00:00') }}"
    endDate: "{{ now() | dateAdd(1, 'HOURS') | date('yyyy-MM-dd HH:00:00') }}"
    wait: true
    maxDuration: PT10M
```

For a daily job, a one-day range already yields a single instance, so the defaults are correct as-is.

When you do want a real backfill, size `maxDuration` for the *total*: roughly `number of periods × per-instance duration ÷ parallel`. Raising `parallel` (max 5) runs periods concurrently. Watching the run in **Monitor Instance** is the quickest way to tell a slow backfill from a stuck one — a run reporting `RUNNING` while instances tick over one by one is working normally.

`runTimeWindow` confines the backfill to a daily `HH:mm-HH:mm` period (sent as `supplement_data_run_time.time_of_day`), e.g. `01:00-05:00` for off-peak. It is omitted by default: the API's documented `00:00-00:00` default reads like a zero-width window but does not restrict execution.

When `wait: true`, the task polls until the run reaches a terminal state and fails the Kestra task unless the status is `success`.

Outputs: `jobName`, `runName`, `status`, `jobList`, `startDate`, `endDate`, `submittedDate`, `parallel`, `userName`.
The three date outputs are ISO-8601 instants (e.g. `2026-07-28T00:00:00Z`), converted from the epoch milliseconds DataArts returns.

**Date format.** DataArts only parses one form, shown in its own API reference as `2023-08-21T00:00:00 +08`: an ISO date-time with a `T` separator, then a space, then a UTC offset. The task converts your value for you, accepting `yyyy-MM-dd`, `yyyy-MM-dd HH:mm:ss` and ISO date-times, and treating a time without an offset as UTC. A value that already ends in a UTC offset is sent through verbatim, so you can reach a form the task doesn't generate.

A date-only `startDate` means the start of that day; a date-only `endDate` means the *end* of it, so `startDate: 2026-07-01` with `endDate: 2026-07-31` covers all of July.

**Gotcha — an unparsed date looks exactly like too short a range.** DataArts compares the two values as timestamps and rejects an equal or inverted pair with `DLF.30121 "The end time should be at least 2 second later than the start time."` — but it reports that same error for a value it could not parse at all, because both ends then collapse to the same instant. Verified live on `ap-southeast-3`: `yyyy-MM-dd`, `yyyy-MM-dd HH:mm:ss` and epoch milliseconds all produce `DLF.30121`, including for a range spanning a whole day. If you hit it, check the dates in the log line above the error before widening the range.

**Gotcha — creation is asynchronous**: the create response carries only a request ID, so the task polls the run list by name until the run appears. If it never does, the task fails with a clear error rather than hanging — the run may have been rejected asynchronously, so check the console's Supplement Data view.

**Gotcha — `runName` must be unique** within the workspace. Reusing a name conflicts with the existing run; leave `runName` unset to get a unique generated one.

### GetJobRun

Fetches the current status of a DataArts Factory job run without polling.

Required: `jobName`, `projectId`.

Optional: `instanceId` — when omitted, the most recently started instance for the job is returned.

Outputs: `jobName`, `instanceId`, `jobInstanceName`, `status`, `planTime`, `startTime`, `endTime`, `executeTime`, `submitTime`, `jobId`.

`planTime`, `startTime`, `endTime` and `submitTime` are ISO-8601 instants, converted from the epoch milliseconds DataArts returns. `executeTime` is a **duration** (`PT4S`), not a timestamp, despite the API naming the field `execute_time` alongside the others — it reports how long the instance took, so `{{ outputs.get_run.executeTime }}` renders as e.g. `PT4S`.

### StopJobRun

Cancels a running supplement-data run — one created by `StartJobRun` — via `POST /v2/{project_id}/factory/supplement-data/{instance_name}/stop`.

**Scope**: this is the only stop route DataArts Factory publishes. There is no API to stop a plain job instance: all of `POST|PUT /v2/{pid}/factory/jobs/{job}/instances/{id}/stop`, `POST .../jobs/{job}/stop`, `POST .../jobs/{job}/instances/stop` and `POST .../jobs/instances/{id}/stop` return `APIGW.0101`, and the SDK declares no factory-job stop route. **A run triggered from the DataArts Studio console therefore cannot be stopped from Kestra** — only one created by `StartJobRun`.

Required: `runName` (the `runName` output of `StartJobRun`), `projectId`.

Optional: `wait` (default `true`), `maxDuration` (default 10 minutes), `interval` (default 3 s).

Outputs: `runName`, `status`, `jobList`, `startDate`, `endDate`, `submittedDate`, `parallel`, `userName` — dates as ISO-8601 instants.

## Supplement-data run statuses

Supplement-data statuses are **upper case** — `SUCCESS`, `RUNNING`, `CANCEL` are the values documented for the `status` query filter, and the reference response returns `"RUNNING"`. Note these are a *different* vocabulary from the lower-case job-instance statuses below; the two are not interchangeable.

No SDK enum exists (the response row types `status` as a plain string) and the docs enumerate only those three filterable values, so matching is case-insensitive and also covers `FAIL`, `FAILED`, `CANCELED`, `CANCELLED`, `STOP` and `STOPPED`. Only `SUCCESS` counts as a successful outcome. An unrecognised value is treated as non-terminal, so the worst case is a timeout rather than a wrongly reported outcome, and `StartJobRun`/`StopJobRun` log every observed status at INFO so any missing value can be spotted in a run's logs.

## Job run statuses

These apply to the plain job instances reported by `GetJobRun`.

| Status | Description |
|---|---|
| `waiting` | Queued, not yet started |
| `running` | Currently executing |
| `success` | Completed successfully |
| `fail` | Completed with an error |
| `manual` | Awaiting manual confirmation |
| `pause` | Paused by user |
| `skip` | Skipped |
| `freeze` | Frozen |

Terminal states (no further transitions): `success`, `fail`, `skip`. `manual`, `pause` and `freeze`
await an operator, so tasks with `wait: true` keep polling them until `maxDuration` elapses.
