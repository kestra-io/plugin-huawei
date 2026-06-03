# QA Plan — plugin-huawei

**This document is the manual QA checklist. Do not execute as part of automated CI; most steps require a real Huawei Cloud tenant.**

- [Part 1 — IAM authentication foundation](#part-1--iam-authentication-foundation) ([kestra-ee#7868](https://github.com/kestra-io/kestra-ee/issues/7868))
- [Part 2 — OBS Object Storage Service](#part-2--obs-object-storage-service) ([kestra-ee#7865](https://github.com/kestra-io/kestra-ee/issues/7865))

# Part 1 — IAM authentication foundation

Tracks issue [kestra-ee#7868](https://github.com/kestra-io/kestra-ee/issues/7868) — `ConnectionUtils` and `GetToken`.

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
- [ ] `./gradlew test` → all IAM tests green (`1 pending` from the disabled live test). Note: the full suite now also contains OBS tests gated by `OBS_MINIO_TESTS` — see Part 2.
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

- [ ] `io.kestra.plugin.huawei` `package-info.java` declares `@PluginSubGroup(title = "Huawei", …, categories = CLOUD)`.
- [ ] No other code in the plugin was modified (use `git diff main --stat`).

## Sign-off

| Role | Name | Date | Result |
|------|------|------|--------|
| Plugin author |  |  |  |
| QA |  |  |  |
| Huawei partner reviewer |  |  |  |

## Known gaps (out of scope for this issue, tracked separately)

- ~~Full AK/SK signing for downstream service requests~~ — delivered with OBS (the SDK signs requests with AK/SK; see Part 2).
- Token caching / auto-refresh — out of scope; users compose `GetToken` themselves in their flows.
- Token revocation task — out of scope.

# Part 2 — OBS Object Storage Service

Tracks issue [kestra-ee#7865](https://github.com/kestra-io/kestra-ee/issues/7865) — the S3-equivalent task family.

## Scope of these changes

- New package `io.kestra.plugin.huawei.obs.tasks` with `Upload`, `Download`, `Downloads`, `List`, `Copy` (`delete=true` → move), `Delete`, `DeleteList` (batched ≤1000), `CreateBucket` (idempotent), and a polling `Trigger` (downloads new objects, then applies a `NONE`/`DELETE`/`MOVE` action to avoid re-triggering).
- Shared layer in `io.kestra.plugin.huawei.obs`: `AbstractObs`/`AbstractObsObject`, `ObsService` (client factory, download, paginated list), `ObsUtils` (endpoint resolution), `AuthType` (`OBS`/`V2`/`V4`), `ListInterface`, `ActionInterface`, `models.ObsObject`.
- SDK: shaded `com.huaweicloud:esdk-obs-java-bundle:3.25.5`.
- **Auth model:** OBS uses AK/SK request signing only — an IAM `X-Auth-Token` from `GetToken` is *not* valid for object operations. `securityToken` is passed through for temporary credentials.

## Pre-requisites

1. A Huawei Cloud account with an IAM user holding `OBS OperateAccess` (or `OBS Administrator`) and a generated AK/SK pair.
2. A reachable OBS region (e.g., `eu-west-101`, `ap-southeast-1`).
3. Docker available locally for the MinIO-backed automated tests.

## QA checklist

### A. Build & automated tests (MinIO — no tenant required)

- [ ] `docker compose up -d minio` then `OBS_MINIO_TESTS=true ./gradlew clean test` → **48 tests, 1 skipped, 0 failures**.
- [ ] Run the suite twice more — no flakiness from bucket-name collisions (each test class creates a unique bucket).
- [ ] `./gradlew shadowJar` succeeds; the jar bundles the shaded OBS SDK (no `okhttp`/`slf4j` leakage):
  ```sh
  unzip -l build/libs/plugin-huawei-*.jar | grep -cE 'org/slf4j' # expect 0
  ```

> **Important:** MinIO validates the S3-compatible `V2` signing path only. Native OBS signing (`AuthType.OBS`, the default) **must** be validated against a real tenant — sections B–E below.

### B. Live OBS smoke — full task family

Deploy the shadow jar to a local Kestra instance (as in Part 1 §D) and run a flow chaining every task against a real OBS bucket with only `accessKeyId`/`secretAccessKey`/`region` set (no `endpointOverride`, no `authType` — exercising defaults):

1. `CreateBucket` (new bucket name) → SUCCESS; re-run → still SUCCESS (idempotency on a bucket you own).
2. `Upload` a small file from internal storage → SUCCESS; object visible in the OBS console; `file.size` metric emitted.
3. `List` with a `prefix` matching the upload and a `regexp` excluding it → object present / absent respectively; `size` metric matches.
4. `Download` the object → output URI is a `kestra:///` internal-storage URI; content byte-identical to the upload; `contentType` and user metadata round-trip.
5. `Copy` to a second key with `delete: true` → destination exists, source gone (move semantics).
6. `Downloads` with `action: MOVE` and `moveTo.keyPrefix` → all matched objects downloaded and relocated under the prefix (note: destination key is `<keyPrefix><originalKey>`, the full original key is preserved).
7. `DeleteList` with a prefix matching >1000 objects (generate with a loop task or CLI) → all deleted; `count` metric correct (validates batching).
8. `Delete` on a non-existent key → behaves per schema description (no crash with an unhelpful error).

- [ ] All of the above pass.
- [ ] Pagination: `List` on a bucket with >1000 objects and `maxKeys: 100` returns the full set (validates marker loop).

### C. Live Trigger QA

1. Register a flow with the `Trigger` (`interval: PT30S`, a `prefix`, default `action: DELETE`).
2. Upload 2 matching objects + 1 non-matching object to the bucket.
- [ ] One execution fires with exactly the 2 matching objects in the trigger output; files land in internal storage.
- [ ] The 2 source objects are deleted from the bucket; the non-matching one remains.
- [ ] No further executions fire on subsequent polls (no re-trigger loop).
- [ ] Repeat with `action: MOVE` → objects relocated instead of deleted, still no re-trigger.

### D. Auth & error matrix

- [ ] **AK/SK only** (sections B–C above) → SUCCESS.
- [ ] **Temporary credentials:** obtain temp AK/SK + `securityToken` (console or STS) and set all three → SUCCESS.
- [ ] **Missing AK or SK:** expect a clean failure whose message explains that OBS requires AK/SK signing and that IAM token auth is not supported.
- [ ] **Wrong SK:** expect FAILED with an OBS `403 SignatureDoesNotMatch`-style error surfaced in logs.
- [ ] **No region and no endpointOverride:** expect a clean `IllegalArgumentException` from endpoint resolution.

### E. Endpoint override & S3-compatible mode

- [ ] `endpointOverride: https://obs.<region>.myhuaweicloud.eu` (EU sovereign cloud) with a matching tenant → SUCCESS.
- [ ] Against MinIO (`endpointOverride: http://localhost:9000`, `pathStyleAccess: true`, `authType: V2`) → `Upload`/`Download` work (mirrors the automated tests; sanity-checks the documented S3-compat recipe).
- [ ] `httpsOnly` is derived from the endpoint scheme: an `http://` override works without TLS errors.

### F. UI, docs & security

- [ ] Plugin tree shows **Huawei → Obs** under the *Storage* category with the bundled icons rendered.
- [ ] Every task/trigger page renders its example YAML; examples reference credentials via `{{ secret('...') }}` only.
- [ ] `accessKeyId`, `secretAccessKey`, `securityToken` are masked in the UI and execution metadata (`secret = true`, requires Kestra ≥ 1.3.13 — `gradle.properties` pins 1.3.13).
- [ ] No AK/SK/token plaintext in task logs at DEBUG level (run an Upload with debug logging and grep the logs).
- [ ] Metrics tab on executions shows `file.size` (Upload/Download), `size` (List/Downloads), `count` (DeleteList).

## Sign-off

| Role | Name | Date | Result |
|------|------|------|--------|
| Plugin author |  |  |  |
| QA |  |  |  |
| Huawei partner reviewer |  |  |  |

## Known gaps (out of scope for this issue, tracked separately)

- IAM→OBS credential exchange (`POST /v3.0/OS-CREDENTIAL/securitytokens` to derive temp AK/SK from a token) — follow-up task.
- Multipart upload for very large files — the SDK transparently handles streams, but no explicit part-size tuning is exposed.
- Bucket lifecycle/policy management tasks — only `CreateBucket` is in scope.
