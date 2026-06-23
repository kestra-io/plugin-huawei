# How to use the FunctionGraph plugin

Synchronously invokes [Huawei Cloud FunctionGraph](https://www.huaweicloud.com/product/functiongraph.html) serverless functions and captures their responses in Kestra internal storage.

## Authentication

FunctionGraph tasks authenticate using AK/SK request signing (Huawei Cloud HMAC-SHA256). Provide `accessKeyId` and `secretAccessKey` via [Kestra secrets](https://kestra.io/docs/concepts/secret). When working with temporary credentials obtained from IAM, also supply `securityToken`.

Secret properties: `accessKeyId`, `secretAccessKey`, `securityToken`.

Configure shared defaults via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.functiongraph
    values:
      region: eu-west-101
      projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
```

### `endpointSuffix`

Controls the top-level domain used when deriving the FunctionGraph endpoint from `region`. Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the Huawei Cloud European sovereign cloud.

### `endpointOverride`

Overrides the default endpoint derived from `region` and `endpointSuffix`. Use for private endpoints, non-standard deployments, or local tests. When set, `endpointSuffix` is ignored.

## Tasks

### Invoke

Synchronously invokes a FunctionGraph function and stores the response in Kestra internal storage.

Required: `functionUrn`.

Optional: `functionPayload` (map of key/value pairs passed as the function event body).

The `functionUrn` uniquely identifies the function and version. Format: `urn:fss:<region>:<project_id>:function:<pkg>:<name>:<qualifier>`. Find it in the FunctionGraph console under the function's **Configuration** tab.

Outputs: `uri` (internal storage URI of the response), `contentLength` (response size in bytes), `requestId` (`X-Cff-Request-Id` value), `statusCode` (HTTP status code).

**Errors:**

- If the function itself returns a function-level error (HTTP 200 with `status=1`), the task throws `FunctionGraphInvokeException` with the error output and a pointer to LTS logs.
- If the invocation fails with an HTTP 4xx/5xx response, the task throws `FunctionGraphInvokeException` with the HTTP status code and the API error message.
