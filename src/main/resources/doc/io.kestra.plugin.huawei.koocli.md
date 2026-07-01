# How to use the KooCLI plugin

Runs arbitrary [Huawei Cloud KooCLI](https://www.huaweicloud.com/product/cli.html) (`hcloud`) commands in a container, with automatic IAM credential injection from this plugin's `AbstractConnection`.

## Authentication

KooCLI tasks authenticate using AK/SK credentials. The connection properties are injected as environment variables, then used to write a `default` KooCLI profile via `hcloud configure set` before your commands run:

| Env var | Source property |
|---|---|
| `HUAWEICLOUD_SDK_AK` | `accessKeyId` |
| `HUAWEICLOUD_SDK_SK` | `secretAccessKey` |
| `HUAWEICLOUD_SDK_REGION` | `region` |
| `HUAWEICLOUD_SDK_SECURITY_TOKEN` | `securityToken` (temporary credentials) |

KooCLI does not read region (or any credential) from an environment variable directly; it only accepts `--cli-region`/`--cli-access-key`/... on the command line or a saved profile. The task writes the profile with a command such as `hcloud configure set --cli-profile=default --cli-access-key="$HUAWEICLOUD_SDK_AK" --cli-region="$HUAWEICLOUD_SDK_REGION" ...`, referencing the env vars **by shell name** so the actual secret values are only expanded by the shell inside the isolated container and never appear on argv or in Kestra logs. This makes `region` take effect for every command with no need to pass `--cli-region` manually.

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

Optional: `installScriptUrl` (unset by default; auto-resolved, see below), `containerImage` (default `ubuntu:26.04`), `env`, `outputFiles`, `namespaceFiles`, `inputFiles`, `taskRunner`.

**Automatic install:** if `hcloud` is not present in the container image, two guarded bootstrap steps run first:
1. `command -v curl || apt-get install -y curl ca-certificates tar`: the default `ubuntu:26.04` image ships without `curl`; this installs it when absent.
2. `command -v hcloud || curl ... | bash -s -- -y`: downloads and installs the KooCLI binary (~5 MB) from an automatically resolved install script (see below).

Both steps are skipped automatically when `curl` / `hcloud` are already present (prebuilt images).

**Cloud partition / EU Sovereign Cloud:** the `hcloud` binary is partition-specific. It ships with a region catalog baked in and validates `--cli-region` against it, with no runtime override (`--cli-endpoint` does not bypass this validation). This plugin resolves the correct install script automatically for most users, in three tiers:

1. **`installScriptUrl` explicit override** (if set) always wins: the escape hatch for a private/dedicated (HCS) cloud or any future partition not yet known to this plugin.
2. **Known sovereign region** (currently `eu-west-101`) auto-selects the EU Sovereign Cloud (`myhuaweicloud.eu`) binary.
3. **Everything else** falls back to the international binary, which covers the full standard region catalog (`cn-*`, `ap-*`, `la-*`, `na-*`, `af-*`, `me-*`, `ru-*`, `tr-*`, `ae-*`, `sa-*`, and `eu-west-0`).

Standard regions install the international binary automatically; the EU Sovereign Cloud region `eu-west-101` auto-selects the `myhuaweicloud.eu` binary. Set `installScriptUrl` explicitly only for private/dedicated (HCS) clouds or future partitions:

```yaml
- id: cli
  type: io.kestra.plugin.huawei.koocli.KooCLI
  region: hcs-region-1
  installScriptUrl: https://hcloudcli.my-hcs-domain.example.com/cli/latest/hcloud_install.sh
  accessKeyId: "{{ secret('HUAWEI_ACCESS_KEY_ID') }}"
  secretAccessKey: "{{ secret('HUAWEI_SECRET_ACCESS_KEY') }}"
  commands:
    - hcloud IAM KeystoneListProjects --cli-output=json
```

`installScriptUrl` is ignored when `hcloud` is already installed in the container image (the install step is guarded).

**Output format:** KooCLI does not support a global output format setting. Pass `--cli-output=json` (or `table`, `tsv`) per command:
```sh
hcloud IAM KeystoneListProjects --cli-output=json
```

**Glibc requirement:** KooCLI is a dynamically linked binary. Alpine/musl-based images are not supported. Use an Ubuntu/Debian-based image.

**Prebuilt image:** point `containerImage` at an image that already has `hcloud` installed to skip both bootstrap steps entirely:

```dockerfile
FROM ubuntu:26.04
RUN apt-get update && apt-get install -y --no-install-recommends curl bash tar ca-certificates \
    && curl -sSL https://ap-southeast-3-hwcloudcli.obs.ap-southeast-3.myhuaweicloud.com/cli/latest/hcloud_install.sh \
       | bash -s -- -y \
    && echo y | hcloud version >/dev/null 2>&1 \
    && rm -rf /var/lib/apt/lists/*
```

Use the `::{"outputs":{"key": value}}::` pattern in a command to capture values as task output variables accessible via `{{ outputs.task_id.vars.key }}`.
