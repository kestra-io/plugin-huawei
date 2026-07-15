# How to use the SMN plugin

Publishes notification messages to [Huawei Cloud SMN (Simple Message Notification)](https://www.huaweicloud.com/product/smn.html) topics, the Huawei equivalent of AWS SNS.

## Authentication

SMN tasks authenticate using AK/SK request signing (Huawei Cloud HMAC-SHA256). Provide `accessKeyId` and `secretAccessKey` via [Kestra secrets](https://kestra.io/docs/concepts/secret). When working with temporary credentials obtained from IAM, also supply `securityToken`, or configure `temporaryCredentials` for inline IAM credential exchange.

Secret properties: `accessKeyId`, `secretAccessKey`, `securityToken`.

Configure shared defaults via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.smn
    values:
      region: eu-west-101
      projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
```

### `endpointSuffix`

Controls the top-level domain used when deriving the SMN endpoint from `region`. Defaults to `myhuaweicloud.com`. Set to `myhuaweicloud.eu` for the Huawei Cloud European sovereign cloud.

### `endpointOverride`

Overrides the default endpoint derived from `region` and `endpointSuffix`. Use for private endpoints, non-standard deployments, or local tests. When set, `endpointSuffix` is ignored.

## Gotchas

- **`projectId` is required with a custom endpoint**: SMN's v2 APIs embed the project ID in the request path (`/v2/{project_id}/notifications/topics/{topic_urn}/publish`). The SDK auto-discovers the project only when resolving the endpoint from its region enum; a custom endpoint (`endpointOverride` or `endpointSuffix`) bypasses that, so `Publish` fails fast requiring `projectId` whenever a custom endpoint is set.
- **Topics are identified by URN, not ARN**: `topicUrn` follows the format `urn:smn:<region>:<project_id>:<topic_name>`, found on the topic's detail page in the console.
- **`Publish` sends exactly one message per call**: unlike some AWS SNS batch-style integrations, there is no multi-message loop — call the task once per notification.
- **Exactly one message mode**: `message`, `messageStructure`, or `messageTemplateName` must be set — never zero, never more than one. `tags` is only meaningful together with `messageTemplateName`.
- **`messageStructure` needs a `default` entry**: SMN falls back to it for any subscription protocol not explicitly listed in the map.
- **`subject` is email-only**: SMN ignores it for non-email subscription protocols (SMS, HTTP, DMS, ...).

## Tasks

### Publish

Publishes a single message to an SMN topic.

Required: `topicUrn`, and exactly one of `message`, `messageStructure`, `messageTemplateName`.

Optional: `subject` (email-only), `tags` (template placeholder values, only with `messageTemplateName`), `messageAttributes` (list of `name`/`type`/`value`; only `STRING` type is currently supported), `timeToLive`.

Outputs: `messageId`, `requestId`.
