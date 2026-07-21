# How to use the RFS plugin

Creates, updates, and deletes Terraform/HCL-based resource stacks via [Huawei Cloud RFS (Resource Formation Service)](https://www.huaweicloud.com/product/rfs.html), the Huawei equivalent of AWS CloudFormation.

## Authentication

RFS tasks authenticate using AK/SK request signing (Huawei Cloud HMAC-SHA256). Provide `accessKeyId` and `secretAccessKey` via [Kestra secrets](https://kestra.io/docs/concepts/secret). When working with temporary credentials obtained from IAM, also supply `securityToken`, or configure `temporaryCredentials` for inline IAM credential exchange.

Secret properties: `accessKeyId`, `secretAccessKey`, `securityToken`.

Configure shared defaults via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.rfs
    values:
      region: eu-west-101
      projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
```

### `endpointSuffix`

Controls the top-level domain used when deriving the RFS endpoint from `region`. Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the Huawei Cloud European sovereign cloud (`eu-west-101`).

### `endpointOverride`

Overrides the default endpoint derived from `region` and `endpointSuffix`. Use for private endpoints, non-standard deployments, or local tests. When set, `endpointSuffix` is ignored.

## Gotchas

- **`projectId` is required with a custom endpoint**: RFS's v1 APIs embed the project ID in the request path (`/v1/{project_id}/stacks/...`). The SDK auto-discovers the project only when resolving the endpoint from its region enum; a custom endpoint (`endpointOverride` or `endpointSuffix`) bypasses that, so `Create`/`Delete` fail fast requiring `projectId` whenever a custom endpoint is set.
- **RFS deploys Terraform/HCL, not CloudFormation-style templates**: `Create` requires exactly one template source (`templateBody` inline, or `templateUri` on OBS) and allows at most one variables source (`vars` map, `varsBody` inline tfvars, or `varsUri` on OBS).
- **Create/deploy are asynchronous, and there is no SDK waiter**: `createStack`/`deployStack` return immediately with a `deploymentId`; `Create` polls `getStackMetadata` itself when `wait` is `true` (default).
- **Only `DEPLOYMENT_COMPLETE` counts as a successful deploy**: `CREATION_COMPLETE` means the stack shell was created but never actually deployed (unexpected in normal use, since `Create` always supplies a template), and `ROLLBACK_COMPLETE` means the deploy failed and was rolled back to the previous good state. Both, along with `DEPLOYMENT_FAILED`/`ROLLBACK_FAILED`, fail the task.
- **RFS has no `DELETION_COMPLETE` status**: a successfully deleted stack simply stops existing — `Delete` polls until the next `getStackMetadata` call returns HTTP 404.
- **`Delete` is idempotent by default**: deleting an already-absent stack is a no-op logged at `INFO` level. Set `errorOnMissing: true` to fail instead.
- **Sensitive outputs are masked by RFS itself**: `listStackOutputs` returns the literal string `<sensitive>` for any Terraform output marked sensitive, never the real value.
- **`temporaryCredentials` can expire mid-wait on long deployments**: with `wait: true`, `maxDuration` can span hours for a large apply, but the client and its credentials are built once at the start of the task and reused for every poll. If `temporaryCredentials.durationSeconds` is shorter than the deployment, a mid-poll `getStackMetadata` fails on token expiry. Use long-lived AK/SK for long-running stacks, or set `durationSeconds` comfortably above the expected deployment time.

## Tasks

### Create

Creates the stack if it does not exist, otherwise deploys an update.

Required: `stackName`, and exactly one of `templateBody`/`templateUri`.

Optional: `vars`/`varsBody`/`varsUri` (at most one), `stackDescription`, `enableDeletionProtection` (default `false`), `enableAutoRollback` (default `true`), `wait` (default `true`), `maxDuration` (default 1 hour), `interval` (default 5 s).

Outputs: `stackId`, `stackName`, `deploymentId`, `status` (`DEPLOYMENT_COMPLETE` on success, `null` when `wait` is `false`), `outputs` (`Map<String, String>` of the stack's declared Terraform outputs, empty when `wait` is `false`).

### Delete

Deletes a stack and, by default, waits for it to be fully removed.

Required: `stackName`.

Optional: `errorOnMissing` (default `false` — treats a missing stack as a no-op), `wait` (default `true`), `maxDuration` (default 1 hour), `interval` (default 5 s).
