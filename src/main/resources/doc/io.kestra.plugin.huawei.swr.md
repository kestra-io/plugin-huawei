# How to use the SWR plugin

Fetches short-lived Docker/OCI registry credentials from [Huawei Cloud SWR (Software Repository for Container)](https://www.huaweicloud.com/product/swr.html), the Huawei equivalent of AWS ECR.

## Authentication

SWR tasks authenticate using AK/SK request signing (Huawei Cloud HMAC-SHA256). Provide `accessKeyId` and `secretAccessKey` via [Kestra secrets](https://kestra.io/docs/concepts/secret). When working with temporary credentials obtained from IAM, also supply `securityToken`, or configure `temporaryCredentials` for inline IAM credential exchange.

Secret properties: `accessKeyId`, `secretAccessKey`, `securityToken`.

Configure shared defaults via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.swr
    values:
      region: eu-west-101
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
```

### `endpointSuffix`

Controls the top-level domain used when deriving the SWR endpoint from `region`. Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the Huawei Cloud European sovereign cloud.

### `endpointOverride`

Overrides the default endpoint derived from `region` and `endpointSuffix`. Use for private endpoints, non-standard deployments, or local tests. When set, `endpointSuffix` is ignored.

## Gotchas

- **No `projectId` requirement with a custom endpoint**: unlike CES, SMN, DLI, EventGrid, and MRS, SWR's `createSecret` API (`/v2/manage/utils/secret`) has no `{project_id}` segment in its request path, so `GetAuthToken` works with a custom endpoint even when `projectId` is unset.
- **Fixed credential lifetime**: SWR's `createSecret` API has no request parameter to control how long the issued credential stays valid — unlike AWS ECR, the validity period is entirely SWR-controlled. `expiry` in the output reflects whatever SWR returns.
- **Registry host comes from the response, not from `region`**: `GetAuthToken` reads the registry host from the `auths` map key returned by SWR rather than reconstructing it, so it is correct even for sovereign clouds without needing `endpointSuffix` reasoning on the caller's side.

## Tasks

### GetAuthToken

Obtains a short-lived registry credential via `createSecret`.

Optional: `projectName` (defaults to `region`).

Outputs: `username`, `password` (encrypted), `registry`, `expiry`.
