# How to use the KooCLI plugin

Runs arbitrary [Huawei Cloud KooCLI](https://www.huaweicloud.com/product/cli.html) (`hcloud`) commands in a container, with automatic IAM credential injection from this plugin's `AbstractConnection`.

## Authentication

KooCLI tasks authenticate using AK/SK credentials, injected as environment variables:

| Env var | Source property |
|---|---|
| `HUAWEICLOUD_SDK_AK` | `accessKeyId` |
| `HUAWEICLOUD_SDK_SK` | `secretAccessKey` |
| `HUAWEICLOUD_SDK_REGION` | `region` |
| `HUAWEICLOUD_SDK_SECURITY_TOKEN` | `securityToken` (temporary credentials) |

Provide credentials via [Kestra secrets](https://kestra.io/docs/concepts/secret). The `temporaryCredentials` inline exchange is also supported: configure it once via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) and every KooCLI task receives freshly exchanged STS credentials without per-task wiring.

```yaml
pluginDefaults:
  - type: io.kestra.plugin.huawei.koocli
    values:
      region: eu-west-101
      accessKeyId: "{{ secret('HUAWEI_AK') }}"
      secretAccessKey: "{{ secret('HUAWEI_SK') }}"
```

## Tasks

### KooCLI

Runs one or more `hcloud` commands in a container and returns a `ScriptOutput` with exit code, captured output variables, and any output files.

Required: `commands`.

Optional: `containerImage` (default `ubuntu:22.04`), `env`, `outputFiles`, `namespaceFiles`, `inputFiles`, `taskRunner`.

**Automatic install:** if `hcloud` is not present in the container image, two guarded bootstrap steps run first:
1. `command -v curl || apt-get install -y curl ca-certificates tar` — the default `ubuntu:22.04` image ships without `curl`; this installs it when absent.
2. `command -v hcloud || curl ... | bash -s -- -y` — downloads and installs the KooCLI binary (~5 MB).

Both steps are skipped automatically when `curl` / `hcloud` are already present (prebuilt images).

**Output format:** KooCLI does not support a global output format setting. Pass `--cli-output=json` (or `table`, `tsv`) per command:
```sh
hcloud OBS ListBuckets --cli-output=json
```

**Glibc requirement:** KooCLI is a dynamically linked binary. Alpine/musl-based images are not supported. Use an Ubuntu/Debian-based image.

**Prebuilt image:** point `containerImage` at an image that already has `hcloud` installed to skip both bootstrap steps entirely:

```dockerfile
FROM ubuntu:22.04
RUN apt-get update && apt-get install -y --no-install-recommends curl bash tar ca-certificates \
    && curl -sSL https://ap-southeast-3-hwcloudcli.obs.ap-southeast-3.myhuaweicloud.com/cli/latest/hcloud_install.sh \
       | bash -s -- -y \
    && echo y | hcloud version >/dev/null 2>&1 \
    && rm -rf /var/lib/apt/lists/*
```

Use the `::{"outputs":{"key": value}}::` pattern in a command to capture values as task output variables accessible via `{{ outputs.task_id.vars.key }}`.
