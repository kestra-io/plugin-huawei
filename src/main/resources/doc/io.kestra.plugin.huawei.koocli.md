# How to use the KooCLI plugin

Runs arbitrary [Huawei Cloud KooCLI](https://www.huaweicloud.com/product/cli.html) (`hcloud`) commands in a container, with automatic IAM credential injection from this plugin's `AbstractConnection`.

## Authentication

KooCLI tasks authenticate using AK/SK credentials, which are injected as `HUAWEICLOUD_SDK_AK` and `HUAWEICLOUD_SDK_SK` environment variables. Provide them via [Kestra secrets](https://kestra.io/docs/concepts/secret).

The `temporaryCredentials` inline exchange is also supported: configure it once via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) and every KooCLI task receives freshly exchanged STS credentials without per-task wiring.

Secret properties: `accessKeyId`, `secretAccessKey`, `securityToken`.

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

Optional: `containerImage` (default `ubuntu:22.04`), `outputFormat` (`JSON`/`TABLE`/`TSV`, default `JSON`), `env`, `outputFiles`, `namespaceFiles`, `inputFiles`, `taskRunner`.

**Automatic install:** if `hcloud` is not present in the container image, the task downloads and installs it automatically (~5 MB) from the official Huawei distribution bucket. The step is guarded by `command -v hcloud` and is skipped when the binary is already available — use a prebuilt image to avoid the per-run download.

**Glibc requirement:** KooCLI is a dynamically linked binary. Alpine/musl-based images are not supported. Use an Ubuntu/Debian-based image.

**Prebuilt image:** point `containerImage` at an image that already has `hcloud` installed to skip the install step entirely:

```dockerfile
FROM ubuntu:22.04
RUN apt-get update && apt-get install -y --no-install-recommends curl bash tar ca-certificates \
    && curl -sSL https://ap-southeast-3-hwcloudcli.obs.ap-southeast-3.myhuaweicloud.com/cli/latest/hcloud_install.sh \
       | bash -s -- -y \
    && echo y | hcloud version >/dev/null 2>&1 \
    && rm -rf /var/lib/apt/lists/*
```

Region and output format are pre-configured via `hcloud configure set` before user commands run, so commands do not need to repeat `--cli-region`.

Use the `::{"outputs":{"key": value}}::` pattern in a command to capture values as task output variables accessible via `{{ outputs.task_id.vars.key }}`.
