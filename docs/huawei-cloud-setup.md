# Huawei Cloud account & API access — dev and CI setup

Findings and recommended setup for [issue #2](https://github.com/kestra-io/plugin-huawei/issues/2):
how to obtain Huawei Cloud credentials for local development, which auth mechanism the plugin
defaults to, the least-privilege IAM policy for the dev/CI user, and how CI consumes the credentials.

## TL;DR

| Question | Recommendation |
|---|---|
| Account tier | One main Huawei Cloud account; **all credentials belong to IAM sub-users**, never the root account |
| Auth mechanism | **AK/SK signing is the default** for SDK-backed tasks; short-lived IAM tokens (via `auth.GetToken`) are the supported alternative |
| SDKs | `com.huaweicloud:esdk-obs-java-bundle` for OBS (storage); `com.huaweicloud.sdk:huaweicloud-sdk-*` (v3) for other services; raw signed REST only as a fallback |
| CI credentials | Dedicated `kestra-ci` IAM user with its own AK/SK, stored as GitHub Actions secrets |
| CI tests | Unit tests mock HTTP (WireMock); live integration tests are `@Disabled` locally and env-gated in CI |

## 1. Account model

Huawei Cloud has a single account tier (the *account*, a.k.a. *domain*) plus **IAM sub-users**
inside it. Relevant constraints found during the investigation:

- Each user (including the root account) can hold a **maximum of two access keys**, and the
  quota is not increasable. This rules out sharing one user's keys between developers and CI —
  create one IAM user per consumer instead.
- The root account should never own programmatic credentials; create IAM users with
  **programmatic access only** (no console password) for dev and CI.
- Two IDs matter for API scoping, both visible under **My Credentials → API Credentials**:
  - **Project ID** — region-scoped (one per region), used by regional services (OBS, ECS, …).
    Maps to the plugin's `projectId` connection property.
  - **Domain ID** — identifies the account, used by global services (IAM administration).
    Maps to the plugin's `domainId` connection property.

Recommended user layout:

| IAM user | Purpose | Credentials |
|---|---|---|
| `<dev-name>` | local development | personal AK/SK |
| `kestra-ci` | GitHub Actions | dedicated AK/SK, stored as repo secrets |

## 2. Obtaining credentials (local dev)

1. Log in to the [Huawei Cloud console](https://console-intl.huaweicloud.com/) with an account
   that has IAM admin rights.
2. **IAM → Users → Create User** — programmatic access only, no console password.
3. Add the user to a group carrying the policy from [section 5](#5-least-privilege-iam-policy)
   (or `OBS OperateAccess` while prototyping).
4. As that user (or via **IAM → Users → Security Settings**): **My Credentials → Access Keys →
   Create Access Key**. This downloads a `credentials.csv` with the **Access Key ID (AK)** and
   **Secret Access Key (SK)**. The SK is shown only once — store it in a secret manager.
5. Note your **region** (e.g. `eu-west-101`, `ap-southeast-1`, `cn-north-4`), **Project ID**,
   and **Domain ID** from **My Credentials → API Credentials**.

### Validating the credentials

Token-based validation (no SDK required) — this is exactly what the
`io.kestra.plugin.huawei.auth.GetToken` task does:

```bash
curl -si "https://iam.<region>.myhuaweicloud.com/v3/auth/tokens" \
  -H "Content-Type: application/json;charset=utf-8" \
  -d '{
    "auth": {
      "identity": {
        "methods": ["password"],
        "password": {
          "user": {
            "name": "<iam-username>",
            "password": "<password>",
            "domain": { "name": "<account-name>" }
          }
        }
      },
      "scope": { "project": { "name": "<region>" } }
    }
  }' | grep -i x-subject-token
```

A `201` response with an `X-Subject-Token` header confirms the account, user, and domain are
wired correctly. AK/SK validation is easiest through an SDK call (e.g. `ObsClient.listBuckets`)
since manual SDK-HMAC-SHA256 signing is error-prone.

## 3. Recommended auth mechanism: AK/SK (with token as alternative)

**AK/SK request signing is the plugin default**, matching Huawei's own guidance for SDK/API/CLI
usage:

- No expiry mid-flow: tokens live 24 h and would need refresh/caching logic inside long-running
  executions or triggers; AK/SK signs each request independently.
- It is what both official Java SDKs (`esdk-obs-java`, `huaweicloud-sdk-*` v3) consume natively.
- It mirrors the AWS plugin's `accessKeyId`/`secretAccessKey` model, keeping the Kestra user
  experience consistent across cloud plugins.

**Token-based auth remains supported** for users who cannot distribute long-lived keys: the
`auth.GetToken` task obtains a short-lived token from the Keystone v3 endpoint, and downstream
tasks accept it via the `securityToken` connection property (sent as `X-Auth-Token`).

Mapping to the connection properties in
[`AbstractConnectionInterface`](../src/main/java/io/kestra/plugin/huawei/AbstractConnectionInterface.java):

| Property | Auth path | Source in console |
|---|---|---|
| `accessKeyId` | AK/SK | `credentials.csv` (AK) |
| `secretAccessKey` | AK/SK | `credentials.csv` (SK) |
| `securityToken` | token | output of `auth.GetToken` (`{{ outputs.get_token.token.tokenValue }}`) |
| `projectId` | both | My Credentials → API Credentials |
| `domainId` | both (global services) | My Credentials → API Credentials |
| `region` | both | e.g. `eu-west-101` |
| `iamEndpointOverride` | both | only for sovereign clouds (`*.myhuaweicloud.eu`) or private endpoints |

## 4. SDK decision

An official, maintained Java SDK exists for every service in scope — **prefer SDKs over signed
REST via Kestra's HTTP client**:

- **OBS (storage)**: [`com.huaweicloud:esdk-obs-java-bundle`](https://github.com/huaweicloud/huaweicloud-sdk-java-obs)
  — S3-compatible, dedicated client, endpoint `obs.<region>.myhuaweicloud.com`.
- **Everything else** (ECS, FunctionGraph, DLI, SMN, …):
  [`com.huaweicloud.sdk:huaweicloud-sdk-<service>`](https://github.com/huaweicloud/huaweicloud-sdk-java-v3)
  v3, with `BasicCredentials` (regional) or `GlobalCredentials` (global services).
- **Raw REST** is only used where no SDK call fits — e.g. `GetToken` posts directly to
  `/v3/auth/tokens` with the JDK HTTP client, because the token endpoint is trivial and
  pulling the IAM SDK in for one call is not worth the dependency.

Endpoint structure is uniformly `https://<service>.<region>.myhuaweicloud.com`
(`myhuaweicloud.eu` for the European sovereign cloud), parameterized by the `region` property
with `iamEndpointOverride` as the escape hatch.

## 5. Least-privilege IAM policy

Attach this custom policy (IAM → Permissions → Policies → Create Custom Policy, JSON view) to
the group containing the dev and CI users. It is scoped to OBS — the first service the plugin
targets — and restricted to buckets prefixed `kestra-ci-`:

```json
{
  "Version": "1.1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "obs:bucket:CreateBucket",
        "obs:bucket:DeleteBucket",
        "obs:bucket:ListBucket",
        "obs:bucket:HeadBucket",
        "obs:object:GetObject",
        "obs:object:PutObject",
        "obs:object:DeleteObject"
      ],
      "Resource": [
        "obs:*:*:bucket:kestra-ci-*",
        "obs:*:*:object:kestra-ci-*/*"
      ]
    }
  ]
}
```

Notes:

- Issuing an IAM token (`GetToken`) requires **no policy at all** — any IAM user can
  authenticate; the policy only governs what the resulting token/AK can do.
- Extend the `Action` list service-by-service as new tasks land (e.g. add `smn:topic:publish`
  when an SMN task is added), keeping the resource scoping pattern.

## 6. CI credential plan (GitHub Actions)

Create a dedicated `kestra-ci` IAM user (its own AK/SK — see the two-keys-per-user quota above)
and add these **repository secrets**. The names match what the tests already read via
`System.getenv` and map 1:1 to the connection properties:

| GitHub Actions secret | Connection property / usage |
|---|---|
| `HUAWEI_ACCESS_KEY_ID` | `accessKeyId` |
| `HUAWEI_SECRET_ACCESS_KEY` | `secretAccessKey` |
| `HUAWEI_PROJECT_ID` | `projectId` |
| `HUAWEI_DOMAIN_ID` | `domainId` |
| `HUAWEI_REGION` | `region` (e.g. `eu-west-101`) |
| `HUAWEI_USERNAME` | `auth.GetToken` `username` (token-path tests only) |
| `HUAWEI_PASSWORD` | `auth.GetToken` `password` (token-path tests only) |
| `HUAWEI_DOMAIN_NAME` | `auth.GetToken` `userDomain` (token-path tests only) |

The repo's `main.yml` delegates to the shared `kestra-io/actions` plugins workflow with
`secrets: inherit`, so secrets added at the repo (or org) level are available to test runs
without workflow changes.

Rotation: regenerate the `kestra-ci` AK/SK periodically (the two-key quota allows creating the
new key before revoking the old one, enabling zero-downtime rotation).

## 7. Cost, free tier, and CI testing strategy

- **IAM is free** — token issuance and identity operations cost nothing, so the `GetToken`
  live test has zero cost.
- **OBS** is covered by a free tier in most regions, and CI-scale usage (a few
  small objects per run in a `kestra-ci-*` bucket) is effectively free; the main risks are
  flakiness and leaked resources, not cost. Integration tests must clean up the
  objects/buckets they create.
- Other services (ECS, etc.) can incur real cost — any future task targeting a billable
  service must keep its live tests gated and prefer mocks.

Resulting test strategy (already in place for `auth`):

1. **Unit tests run always** — request building, response parsing, and error paths are tested
   against mocked HTTP (no network), so PRs from forks need no secrets.
2. **Live integration tests are `@Disabled` by default** (see `GetTokenTest`) and only run when
   the `HUAWEI_*` environment variables are present — i.e. on CI with repo secrets or on a
   developer machine with exported credentials.
3. No always-on real-cloud dependency in CI: a missing/expired secret degrades to skipped
   integration tests, never a red build for contributors.

## References

- [AK/SK Authentication — API Gateway Developer Guide](https://support.huaweicloud.com/intl/en-us/devg-apig/apig-dev-180307021.html)
- [Obtaining an AK/SK — API Request Signing Guide](https://support.huaweicloud.com/intl/en-us/devg-apisign/api-sign-provide-aksk.html)
- [Managing Access Keys for an IAM User](https://support.huaweicloud.com/intl/en-us/usermanual-iam/iam_02_0003.html)
- [Obtaining a User Token Through Password Authentication](https://support.huaweicloud.com/intl/en-us/api-iam/iam_30_0001.html)
- [OBS Java SDK](https://support.huaweicloud.com/intl/en-us/sdk-java-devg-obs/obs_21_0001.html)
- [Huawei Cloud Java SDK v3](https://github.com/huaweicloud/huaweicloud-sdk-java-v3)
