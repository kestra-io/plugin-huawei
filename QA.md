# QA Plan — plugin-huawei IAM authentication foundation

Tracks issue [kestra-ee#7868](https://github.com/kestra-io/kestra-ee/issues/7868) — `ConnectionUtils` and `GetToken`.

**This document is the manual QA checklist. Do not execute as part of automated CI; the steps require a real Huawei Cloud tenant.**

## Scope of these changes

- New package `io.kestra.plugin.huawei.auth` with:
  - `GetToken` — Kestra task that calls Keystone v3 `POST /v3/auth/tokens` and returns the IAM token.
- New plugin-wide auth scaffolding (will be reused by every future Huawei service task):
  - `AbstractConnectionInterface` — connection schema (AK/SK, securityToken, projectId, domainId, region, iamEndpointOverride).
  - `AbstractConnection` — abstract `Task` holding the connection fields + `HuaweiClientConfig` record.
  - `ConnectionUtils` — static helpers (IAM endpoint resolution, request body builder, `expires_at` parser).
- Tests:
  - `ConnectionUtilsTest` — 11 unit tests covering endpoint/URL building, request body construction, and validation.
  - `GetTokenTest` — 4 offline tests via in-process `HttpServer` stub + 1 `@Disabled` live test.
  - `GetTokenFlowTest` — `@KestraTest(startRunner = true)` running `flows/auth-get-token.yaml` against the stub.

## Pre-requisites

1. A Huawei Cloud account with an IAM user that has at least the `IAM ReadOnly` role.
2. Knowledge of:
   - Account (domain) name.
   - IAM user name + password.
   - One project name (e.g., `eu-west-101`) **or** a domain name for domain-scoped tokens.
3. A reachable region: pick `eu-west-101` (Paris) or `ap-southeast-1` (Hong Kong) depending on tenant.
4. Java 21 and Gradle wrapper present in the repo.

## QA checklist

### A. Build & static checks

- [ ] `./gradlew clean compileJava compileTestJava` → BUILD SUCCESSFUL.
- [ ] `./gradlew test` → all 17 tests (`16 passing, 1 pending` from the disabled live test) green.
- [ ] `./gradlew shadowJar` → produces `build/libs/plugin-huawei-*.jar`. Inspect manifest:
  ```sh
  unzip -p build/libs/plugin-huawei-*.jar META-INF/MANIFEST.MF | grep X-Kestra
  ```
  Expect `X-Kestra-Title: Huawei`, `X-Kestra-Group: io.kestra.plugin.huawei`.
- [ ] Confirm no Huawei SDK or HTTP-client transitive dependency was added (only JDK + Kestra platform + Jackson already on the BOM).

### B. Unit tests — stubbed IAM

- [ ] Re-run `./gradlew test --tests "io.kestra.plugin.huawei.*"` and confirm the in-process IAM stub tests pass deterministically across 3 consecutive runs (no port flakiness).
- [ ] Manually inspect logs for the negative-path test `run_surfacesIamErrorBody` to confirm IAM error body is propagated to the exception message.

### C. Live IAM round-trip — `GetToken` task in isolation

Enable the disabled live test:

```sh
export HUAWEI_USERNAME='…'
export HUAWEI_PASSWORD='…'
export HUAWEI_DOMAIN_NAME='…'
export HUAWEI_REGION='eu-west-101'
# remove the @Disabled on GetTokenTest.run_liveHuaweiCloud, then:
./gradlew test --tests "io.kestra.plugin.huawei.auth.GetTokenTest.run_liveHuaweiCloud"
```

- [ ] Test passes and reports a non-null token value.
- [ ] Token expiration is ~24h in the future (`expires_at` parsing works).
- [ ] Use the returned token against the Huawei IAM "Get User Info" endpoint to confirm it is a valid `X-Auth-Token`:
  ```sh
  curl -s -H "X-Auth-Token: <token>" \
    "https://iam.${HUAWEI_REGION}.myhuaweicloud.com/v3/users" | jq
  ```
  Expect HTTP 200 and a JSON user list.

### D. Live IAM round-trip — through a real Kestra instance

1. Drop the shadow jar into a local Kestra dev instance:
   ```sh
   cp build/libs/plugin-huawei-*.jar /path/to/kestra/plugins/
   ```
2. Restart Kestra; confirm the plugin appears in the **Documentation → Plugins → Huawei → Authentication → GetToken** UI.
3. Create the following flow in the Kestra UI:
   ```yaml
   id: huawei_get_token_qa
   namespace: qa.huawei
   tasks:
     - id: get_token
       type: io.kestra.plugin.huawei.auth.GetToken
       region: "{{ secret('HUAWEI_REGION') }}"
       username: "{{ secret('HUAWEI_USERNAME') }}"
       password: "{{ secret('HUAWEI_PASSWORD') }}"
       userDomain: "{{ secret('HUAWEI_DOMAIN_NAME') }}"
       projectName: "{{ secret('HUAWEI_REGION') }}"
   ```
4. Add the required secrets via Kestra's secret manager.
5. **Verifications:**
   - [ ] Execution ends in SUCCESS.
   - [ ] Output `outputs.get_token.token.tokenValue` is shown as `(encrypted)` in the UI (proof that `EncryptedString` masking works on this instance).
   - [ ] `outputs.get_token.token.expirationTime` is an ISO-8601 timestamp ~24h in the future.
   - [ ] In the task logs, no plaintext password or token is visible.
   - [ ] The Kestra UI form for `GetToken` correctly hides `password` under the "connection" group, alongside `accessKeyId`, `secretAccessKey`, `securityToken`, `projectId`, `domainId`, `region`. `iamEndpointOverride` lives under "advanced".

### E. Auth-method matrix

Repeat step D with each of the following scopes; expect SUCCESS for each:

- [ ] **project-scoped:** `projectName` set, `domainName` unset.
- [ ] **domain-scoped:** `domainName` set, `projectName` unset.
- [ ] **invalid (both set):** Expect FAILED execution with an `IllegalArgumentException` message mentioning *"Exactly one of `projectName` or `domainName`"*.
- [ ] **invalid (neither set):** Same error as above.
- [ ] **wrong password:** Expect FAILED execution; logs include `HTTP 401` and IAM's `"The password is wrong."`-style message body.
- [ ] **wrong domain name:** Expect FAILED execution with IAM's `"The IAM user does not exist."`-style message body.

### F. Endpoint override

- [ ] Set `iamEndpointOverride: "https://iam.myhuaweicloud.eu"` (Huawei EU sovereign cloud); leave `region` unset.
- [ ] Confirm SUCCESS using a tenant in that cloud (or expect a clean error message naming the host if the tenant is not in EU).

### G. Documentation

- [ ] In Kestra docs UI, the **Authentication** sub-group description reads:
  > "Tasks that obtain Huawei Cloud IAM authentication material — short-lived tokens that downstream Huawei service tasks consume via the `X-Auth-Token` header."
- [ ] The `GetToken` example YAML embedded in the schema (`@Plugin(examples = …)`) is syntactically valid and renders in the docs viewer.

### H. Security review

- [ ] `secretAccessKey`, `securityToken`, `password` never appear in logs at any log level. Run a search:
  ```sh
  grep -rE '(p4ss|TEST_SECRET|TEST_TOKEN)' build/test-results/
  ```
  Expect zero matches outside the request-body assertion message in `GetTokenTest` (which uses `p4ss` intentionally in a stub).
- [ ] Verify the token returned to downstream tasks survives encryption at rest if Kestra is configured with output encryption (`kestra.encryption.secret-key`).

### I. Regression — existing plugin scaffold

- [ ] `io.kestra.plugin.huawei` `package-info.java` still declares `@PluginSubGroup(title = "Huawei", …, categories = TOOL)` — unchanged.
- [ ] No other code in the plugin was modified (use `git diff main --stat`).

## Sign-off

| Role | Name | Date | Result |
|------|------|------|--------|
| Plugin author |  |  |  |
| QA |  |  |  |
| Huawei partner reviewer |  |  |  |

## Known gaps (out of scope for this issue, tracked separately)

- Full AK/SK signing for downstream service requests (HMAC-SHA256 canonical request) — will be added with the first regional service (likely OBS, see #7864 sub-tasks).
- Token caching / auto-refresh — out of scope; users compose `GetToken` themselves in their flows.
- Token revocation task — out of scope.
