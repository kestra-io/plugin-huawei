# How to use the EventGrid plugin

Publishes events to [Huawei Cloud EventGrid (EG)](https://www.huaweicloud.com/product/eg.html), the Huawei equivalent of AWS EventBridge, so that flows can react to CloudEvents-formatted business events routed through a custom channel.

## Authentication

EventGrid tasks authenticate using AK/SK request signing (Huawei Cloud HMAC-SHA256). Provide `accessKeyId` and `secretAccessKey` via [Kestra secrets](https://kestra.io/docs/concepts/secret). When working with temporary credentials obtained from IAM, also supply `securityToken`, or configure `temporaryCredentials` for inline IAM credential exchange.

Secret properties: `accessKeyId`, `secretAccessKey`, `securityToken`.

Configure shared defaults via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.eventgrid
    values:
      region: eu-west-101
      projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
```

### `endpointSuffix`

Controls the top-level domain used when deriving the EventGrid endpoint from `region`. Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the Huawei Cloud European sovereign cloud — this suffix is **unverified** against a live account (the SDK's region enum has no EU entry and Huawei's own EU documentation shows a different `events.eu-west-101.myhuaweicloud.eu` host); use `endpointOverride` if the derived URL doesn't match your tenant.

### `endpointOverride`

Overrides the default endpoint derived from `region` and `endpointSuffix`. Use for private endpoints, non-standard deployments, or local tests. When set, `endpointSuffix` is ignored.

## Gotchas

- **`projectId` is required with a custom endpoint**: EventGrid's v1 APIs embed the project ID in the request path (`/v1/{project_id}/...`). The SDK auto-discovers the project only when resolving the endpoint from its region enum; a custom endpoint (`endpointOverride` or `endpointSuffix`) bypasses that, so `PutEvents` fails fast requiring `projectId` whenever a custom endpoint is set.
- **No batch chunking**: EventGrid's per-request size cap for `putEvents` is not documented anywhere. `PutEvents` always sends the whole `events` list in a single request and surfaces any size-related API error verbatim rather than guessing a safe chunk size.
- **Partial failures don't hard-fail by default silently**: when `failOnUnsuccessfulEvents` is `false` and at least one event is rejected, the task still succeeds but reports a `WARNING` state so the partial failure is visible in the UI, alongside the per-event error detail in the output file.

## Tasks

### PutEvents

Publishes one or more CloudEvents 1.0 events to an EventGrid custom channel.

Required: `channelId`, `events` (an inline list of events, or a `kestra://` internal storage URI pointing to ION-serialized events). Every event requires `source` and `type`; `id` is auto-generated as a random UUID and `specversion` defaults to `1.0` when omitted.

Optional: `failOnUnsuccessfulEvents` (defaults to `true`).

Outputs: `uri` (ION file with per-event `index`/`eventId`/`errorCode`/`errorMsg` results), `eventCount` (total events submitted), `failedEventCount` (events EventGrid rejected).
