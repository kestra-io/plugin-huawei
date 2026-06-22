# How to use the Huawei Cloud IAM plugin

Exchanges Huawei Cloud [Identity and Access Management (IAM)](https://www.huaweicloud.com/product/iam.html) credentials for short-lived STS temporary access keys.

## Authentication

The IAM plugin itself does not use long-lived AK/SK credentials — it produces them. Two authentication methods drive the initial exchange:

- **PASSWORD** (default): authenticates with a Huawei Cloud IAM username and password. The plugin calls `POST /v3/auth/tokens` on the IAM Keystone endpoint to obtain a session token, then exchanges it for temporary STS credentials. No pre-existing token is required.
- **TOKEN**: exchanges an already-obtained `X-Auth-Token` directly for temporary STS credentials. Use this when you manage the IAM token lifecycle externally.

Secret properties: `password` (PASSWORD method), `token` (TOKEN method), `domainName` (treated as sensitive because it identifies the account).

Always provide secrets via [Kestra secrets](https://kestra.io/docs/concepts/secret):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.iam.tasks
    values:
      region: eu-west-101
      authMethod: PASSWORD
      username: my-iam-user
      password: "{{ secret('HUAWEI_IAM_PASSWORD') }}"
      domainName: "{{ secret('HUAWEI_DOMAIN_NAME') }}"
```

### `endpointSuffix`

Controls the domain suffix used to derive the IAM endpoint URL (`https://iam.<region>.<suffix>`). Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the European sovereign cloud (region `eu-west-101` / EU-Dublin).

### Inline `temporaryCredentials` vs `GetTemporaryCredentials`

The preferred approach is the `temporaryCredentials` nested block on any connection-aware task (OBS, DMS, …). Set it once via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) and every task in the namespace obtains fresh STS credentials automatically without manual output wiring.

Use `GetTemporaryCredentials` only when you need the raw credential values in subsequent steps or in external systems.

## Tasks

### GetTemporaryCredentials

Obtains short-lived STS credentials and exposes them as task outputs for manual wiring into downstream tasks or external systems.

Required properties: `region`, `authMethod`.

Additional required properties per method:

| Method | Required |
|---|---|
| PASSWORD | `username`, `password`, `domainName` |
| TOKEN | `token` |

Optional: `scope` (`PROJECT` / `DOMAIN`, default `PROJECT`), `projectName` (defaults to `region`), `durationSeconds` (900–86400, default 900), `endpointSuffix`.

Outputs: `accessKeyId`, `secretAccessKey`, `securityToken`, `expirationTime`.

### `durationSeconds` range

Huawei Cloud accepts values between 900 (15 minutes) and 86400 (24 hours). Values outside this range are rejected by the IAM endpoint. The default is 900 seconds.

**Note:** for long-running tasks such as `RealtimeTrigger` or `Consume` that may outlive `durationSeconds`, the temporary credentials will expire mid-run. Use long-lived AK/SK properties or schedule a refresh externally in those cases.
