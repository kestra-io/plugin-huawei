# How to use the MRS plugin

Creates and manages [Huawei Cloud MRS (MapReduce Service)](https://www.huaweicloud.com/product/mrs.html) clusters and job steps, the Huawei equivalent of AWS EMR. Unlike EMR, MRS is cluster-based only — Huawei's serverless analog is [DLI](https://www.huaweicloud.com/product/dli.html), already available as the `io.kestra.plugin.huawei.dli` plugin.

## Authentication

MRS tasks authenticate using AK/SK request signing (Huawei Cloud HMAC-SHA256). Provide `accessKeyId` and `secretAccessKey` via [Kestra secrets](https://kestra.io/docs/concepts/secret). When working with temporary credentials obtained from IAM, also supply `securityToken`, or configure `temporaryCredentials` for inline IAM credential exchange.

Secret properties: `accessKeyId`, `secretAccessKey`, `securityToken`, and (on `CreateClusterAndSubmitJob`) `managerAdminPassword`/`nodeRootPassword`.

Configure shared defaults via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.mrs
    values:
      region: eu-west-101
      projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
```

### `endpointSuffix`

Controls the top-level domain used when deriving the MRS endpoint from `region`. Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the Huawei Cloud European sovereign cloud.

### `endpointOverride`

Overrides the default endpoint derived from `region` and `endpointSuffix`. Use for private endpoints, non-standard deployments, or local tests. When set, `endpointSuffix` is ignored.

## Gotchas

- **`projectId` is required with a custom endpoint**: MRS's APIs embed the project ID in the request path (e.g. `/v2/{project_id}/run-job-flow`). The SDK auto-discovers the project only when resolving the endpoint from its region enum; a custom endpoint (`endpointOverride` or `endpointSuffix`) bypasses that, so every MRS task fails fast requiring `projectId` whenever a custom endpoint is set.
- **Two SDK API versions under the hood**: MRS V2 (the vendor-recommended API) has no cluster-detail or delete-cluster endpoint, so cluster status polling and `DeleteCluster` go through the older V1 API instead. Both resolve to the same host, so this is transparent to the flow author.
- **Cluster creation is not a 1:1 port of EMR**: MRS requires a richer property surface than EMR — VPC/subnet placement, per-role node group sizing, and either a manager admin password or a login key pair — modeled as MRS's own properties rather than reused EMR field names.
- **`CreateClusterAndSubmitJob` doesn't return step job IDs directly**: the underlying `runJobFlow` API only returns the new `clusterId`. When `wait` is `true`, job IDs for submitted `steps` are resolved best-effort afterward by listing the cluster's job executions and matching by job name; when `wait` is `false`, `jobIds` is always empty.
- **`CreateClusterAndSubmitJob` never deletes the cluster on kill**: the cluster is a long-lived infrastructure resource, not an ephemeral job owned by the task. Use `DeleteCluster` to tear it down explicitly. `SubmitJob`, in contrast, does cancel its in-flight job on `kill()`, since that job is a Kestra-owned unit of work.
- **Billing is always pay-per-use**: `CreateClusterAndSubmitJob` always creates postpaid clusters — prepaid billing is not exposed, since job-triggered clusters are on-demand resources by nature.
- ⚠️ **Enum wire values are documentation-based, not live-verified**: `clusterType`, `loginMode`, `safeMode`, and `jobType` valid values are sourced from Huawei's public MRS API documentation rather than confirmed against a live cluster creation. Test against a real or WireMock-stubbed endpoint before relying on an unusual combination in production.

## Tasks

### CreateClusterAndSubmitJob

Creates an MRS cluster and, optionally, submits one or more job `steps` to run on it once ready. The Huawei equivalent of `io.kestra.plugin.aws.emr.CreateClusterAndSubmitSteps`.

Required: `clusterName`, `clusterVersion`, `clusterType` (`ANALYSIS`, `STREAMING`, `MIXED`, `CUSTOM`), `components` (Hadoop ecosystem components to install), `availabilityZone`, `vpcName`, `nodeGroups`, `loginMode` (`PASSWORD` or `PUBLICKEY`), `managerAdminPassword`. Either `subnetId` or `subnetName` is required. `loginMode: PASSWORD` also requires `nodeRootPassword`; `loginMode: PUBLICKEY` requires `nodeKeypairName`.

Optional: `steps` (job configs to submit once running), `securityGroupsId` (auto-created if omitted), `safeMode` (defaults to `SIMPLE`), `deleteWhenNoSteps` (defaults to `false`), `enterpriseProjectId`, `logUri`, `wait` (defaults to `true`), `maxDuration` (default 30 minutes), `interval` (default 15 seconds).

Outputs: `clusterId`, `clusterState` (populated only when `wait` is `true`), `jobIds` (best-effort, populated only when `steps` were submitted and `wait` is `true`).

### SubmitJob

Submits a single job step to an already-running MRS cluster. The Huawei equivalent of `io.kestra.plugin.aws.emr.SubmitSteps`, adapted to MRS's one-job-per-call submission API.

Required: `clusterId`, `job` (`jobType`, `jobName`, optional `arguments` and `properties`).

Optional: `wait` (defaults to `true`), `maxDuration` (default 1 hour), `interval` (default 5 seconds).

Outputs: `jobId`, `jobState` (populated only when `wait` is `true`).

### DeleteCluster

Deletes an MRS cluster by ID. The Huawei equivalent of `io.kestra.plugin.aws.emr.DeleteCluster`. Deletion is asynchronous — the cluster transitions to `terminating` shortly after this task returns.

Required: `clusterId`.

Outputs: `clusterId`.
