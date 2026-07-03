# How to use the KooCLI plugin

Runs arbitrary [Huawei Cloud KooCLI](https://www.huaweicloud.com/product/cli.html) (`hcloud`) commands in a container, with automatic IAM credential injection from this plugin's `AbstractConnection`.

## Authentication

KooCLI tasks authenticate using the connection's AK/SK (and, for temporary credentials, a security token). The task injects these as environment variables and writes a `default` KooCLI profile via `hcloud configure set`, referencing the variables **by shell name** so the actual secret values are only expanded inside the isolated container and never appear on argv or in Kestra logs. This makes `region` take effect for every command with no need to pass `--cli-region` manually.

Provide credentials via [Kestra secrets](https://kestra.io/docs/concepts/secret). The `temporaryCredentials` inline exchange is also supported: configure it once via [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults) and every KooCLI task receives freshly exchanged STS credentials without per-task wiring.

`projectId` / `domainId` (inherited from the base connection) are not used by this task; pass any project/domain-scoped parameter directly on the relevant `hcloud` command instead.

## Tasks

See the [`KooCLI`](#io.kestra.plugin.huawei.koocli.KooCLI) task reference below for the full property list and examples covering automatic install, cloud partition / EU Sovereign Cloud resolution, prebuilt images, and output capture.
