@PluginSubGroup(
    title = "GeminiDB for NoSQL (DynamoDB-Compatible API)",
    description = """
        Tasks for Huawei Cloud GeminiDB for NoSQL: put, get, delete, query, and scan items against a
        GeminiDB instance's DynamoDB-Compatible API. Instances are addressed by a per-instance
        `endpoint` connection address, not a region-derived host — `region` is used only for SigV4
        request signing and has no effect on routing.

        ## Credentials are the database account, not IAM

        Unlike every other Huawei Cloud service in this plugin, the DynamoDB-compatible data plane is
        served by the GeminiDB instance itself rather than through the API gateway, so it never
        consults IAM. Set:

        - `accessKeyId` to the database username, `rwuser`
        - `secretAccessKey` to the instance admin password you set when buying the instance

        ```yaml
        - id: get_item
          type: io.kestra.plugin.huawei.geminidb.GetItem
          accessKeyId: rwuser
          secretAccessKey: "{{ secret('GEMINIDB_ADMIN_PASSWORD') }}"
          endpoint: "http://192.168.0.10:8000"
          tableName: persons
          key:
            id: "1"
        ```

        Passing a Huawei IAM AK/SK instead fails with a bare `AccessDeniedException: auth failed`
        that carries no error code and no detail — and it fails that way even when the IAM credential
        is valid and `GeminiDB FullAccess` is correctly scoped to the instance's project, so the
        error is easy to misread as a permissions problem. For the same reason `securityToken` and
        `temporaryCredentials` are not supported by these tasks; they are rejected with an
        explanatory error rather than being sent.

        The data-plane port is **8000** and is fixed at creation. Port 8635 belongs to the underlying
        Cassandra kernel and does not speak the DynamoDB protocol.
        """,
    categories = { PluginSubGroup.PluginCategory.DATA, PluginSubGroup.PluginCategory.CLOUD }
)
package io.kestra.plugin.huawei.geminidb;

import io.kestra.core.models.annotations.PluginSubGroup;
