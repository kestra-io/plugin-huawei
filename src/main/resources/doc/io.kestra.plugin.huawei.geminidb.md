# How to use the GeminiDB plugin

Reads and writes items in [Huawei Cloud GeminiDB for NoSQL](https://www.huaweicloud.com/product/gaussdb-nosql.html) through its DynamoDB-Compatible data-plane API, letting flows put, get, delete, query, and scan table items using the same request shapes as Amazon DynamoDB.

Huawei has no data-plane SDK for GeminiDB (the `huaweicloud-sdk-nosql` SDK is control-plane only, covering instance CRUD), so this plugin uses the **AWS SDK v2** (`software.amazon.awssdk:dynamodb`) as the wire-compatible client — the same way you would connect from boto3 with an explicit `endpoint_url`.

## Authentication

GeminiDB tasks authenticate using AK/SK request signing (AWS SigV4, the protocol its DynamoDB-Compatible API expects). Provide `accessKeyId` and `secretAccessKey` via [Kestra secrets](https://kestra.io/docs/concepts/secret). When working with temporary credentials obtained from IAM, also supply `securityToken`, or configure `temporaryCredentials` for inline IAM credential exchange.

Secret properties: `accessKeyId`, `secretAccessKey`, `securityToken`.

Configure shared defaults via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.geminidb
    values:
      endpoint: "https://192.168.0.10:8635"
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
```

### `endpoint`

The mandatory DynamoDB-compatible connection address of the GeminiDB instance, e.g. `https://192.168.0.10:8635`. Find it on the instance's "Connection Management" page in the Huawei Cloud console. Unlike every other Huawei Cloud service in this plugin, this address is per-instance and **cannot be derived from `region`** — there is no region-derived host to fall back to, so `endpoint` is always required.

### `region`

Used only for AWS SigV4 request signing — GeminiDB routes solely by `endpoint` and `region` has no effect on where the request is sent. Defaults to a placeholder (`cn-north-1`); leave it at its default unless signing requires a specific value.

## Gotchas

- **Numbers are stored as strings**: the AK/SK-to-`AttributeValue` conversion falls back to `toString()` for any type it doesn't special-case, notably numbers — so numeric attributes are written as DynamoDB string (`S`) attributes, not numbers (`N`). Quote or compare them as strings in downstream expressions and in `keyConditionExpression`/`filterExpression` placeholders.
- **`Query`/`Scan` read a single response page**: neither task follows `LastEvaluatedKey` to paginate across multiple pages. `limit` bounds how many items are read per page (1-1000, defaults to 100, enforced at render time rather than with `@Min`/`@Max` — Hibernate Validator has no `ValueExtractor` for `Property<>`). An INFO log message is emitted whenever the response was truncated, so a partial result set isn't missed silently.
- **`region` is signing-only**: unlike every other service in this plugin, changing `region` does not change which GeminiDB instance a task talks to — only `endpoint` does.

## Tasks

### PutItem

Creates or replaces an item — an upsert when the key already exists.

Required: `tableName`, `item` (full item content as a map, including its primary key attributes).

Output: `VoidOutput`.

### GetItem

Retrieves a single item by its primary key.

Required: `tableName`, `key` (full primary key map: partition key, plus sort key when the table defines one).

Output: `row` — the fetched item as a map; **empty** (not an error) when no item matches the key.

### DeleteItem

Deletes a single item by its primary key; no condition expression is applied.

Required: `tableName`, `key`.

Output: `VoidOutput`.

### Query

Executes a Query request using a key condition expression.

Required: `tableName`, `keyConditionExpression` (expression on the table's partition key, and sort key if defined, e.g. `id = :id`), `expressionAttributeValues` (map of `:placeholder` values referenced by `keyConditionExpression` and, if set, `filterExpression`).

Optional: `filterExpression` (additional server-side filter evaluated after the key condition), `limit` (1-1000, default 100), `fetchType` (`STORE`, `FETCH`, `FETCH_ONE`, `NONE`; defaults to `STORE`).

`fetchType` behavior:

- `STORE` (default): writes matching rows to Kestra internal storage as ION.
- `FETCH`: loads all rows (up to `limit`) into memory.
- `FETCH_ONE`: returns only the first row.
- `NONE`: runs the query without fetching any results.

Outputs: `rows` (populated only for `FETCH`), `row` (populated only for `FETCH_ONE`), `uri` (ION file URI, populated only for `STORE`), `size` (number of items fetched or stored).

### Scan

Performs a Scan over the entire table.

Required: `tableName`.

Optional: `filterExpression` (server-side filter applied to scanned items; requires `expressionAttributeValues`), `expressionAttributeValues`, `limit` (1-1000, default 100), `fetchType` (`STORE`, `FETCH`, `FETCH_ONE`, `NONE`; defaults to `STORE`, same behavior as `Query`).

Outputs: `rows`, `row`, `uri`, `size` — same semantics as `Query`.
