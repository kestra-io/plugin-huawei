package io.kestra.plugin.huawei.obs.tasks;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.obs.AbstractObsObject;
import io.kestra.plugin.huawei.obs.ObsService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Download an object from Huawei OBS to Kestra internal storage.",
    description = """
        Downloads an object from the specified OBS bucket and key and stores it in Kestra internal storage.
        The task outputs the internal storage URI so downstream tasks can consume the file.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            code = """
                id: obs_download
                namespace: company.team

                tasks:
                  - id: download
                    type: io.kestra.plugin.huawei.obs.tasks.Download
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: "eu-west-101"
                    bucket: "my-bucket"
                    key: "data/report.csv"
                """
        )
    },
    metrics = {
        @Metric(name = "file.size", type = Counter.TYPE)
    }
)
public class Download extends AbstractObsObject implements RunnableTask<Download.Output> {

    @Schema(
        title = "OBS object key to download.",
        description = "The full key (path) of the object within the bucket, e.g. `data/2024/report.csv`."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> key;

    @Schema(
        title = "Version ID of the object to download.",
        description = "When set, retrieves the specific version of the object. " +
            "Only applicable when bucket versioning is enabled. Omit to download the latest version."
    )
    @PluginProperty(group = "advanced")
    private Property<String> versionId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBucket = runContext.render(bucket).as(String.class).orElseThrow();
        var rKey = runContext.render(key).as(String.class).orElseThrow();
        var rVersionId = runContext.render(versionId).as(String.class).orElse(null);

        try (var obs = client(runContext)) {
            var result = ObsService.download(obs, runContext, rBucket, rKey, rVersionId);
            runContext.metric(Counter.of("file.size", result.contentLength()));
            return Output.builder()
                .uri(result.uri())
                .contentLength(result.contentLength())
                .contentType(result.contentType())
                .metadata(result.metadata())
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Kestra internal storage URI of the downloaded object.")
        private final URI uri;

        @Schema(title = "Size of the object in bytes.")
        private final Long contentLength;

        @Schema(title = "MIME content type of the object, as stored in OBS metadata.")
        private final String contentType;

        @Schema(
            title = "User-defined metadata attached to the object.",
            description = "Bare key/value pairs with OBS metadata prefixes stripped."
        )
        private final Map<String, String> metadata;
    }
}
