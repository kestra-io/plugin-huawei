package io.kestra.plugin.huawei.dataarts.models;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * One supplement-data (PatchData) instance, as returned by
 * {@code GET /v2/{project_id}/factory/supplement-data}.
 *
 * <p>Field names on the wire are <b>snake_case</b> — verified against the {@code @JsonProperty}
 * names on the SDK's {@code SupplementDataRespRows} model: {@code name}, {@code job_list},
 * {@code status}, {@code start_date}, {@code end_date}, {@code submitted_date}, {@code parallel},
 * {@code type}, {@code user_name}. Same convention as {@code JobInstance}; see
 * {@link JobRun} for the failure mode that reading camelCase keys produces.
 *
 * <p>Unlike {@link JobRun}, a supplement-data instance is identified by a caller-chosen
 * {@code name} rather than a server-assigned numeric ID, which is why this model has no
 * {@code instanceId}.
 */
@Builder
@Getter
public class SupplementDataRun {

    @Schema(title = "Supplement-data instance name (chosen by the caller when the run was created)")
    private final String name;

    @Schema(title = "Names of the jobs covered by this supplement-data run")
    private final List<String> jobList;

    @Schema(title = "Current status of the supplement-data run")
    private final String status;

    @Schema(title = "Start of the covered business-date range (epoch milliseconds)")
    private final Long startDate;

    @Schema(title = "End of the covered business-date range (epoch milliseconds)")
    private final Long endDate;

    @Schema(title = "Time the run was submitted (epoch milliseconds)")
    private final Long submittedDate;

    @Schema(title = "Number of instances executed in parallel")
    private final Integer parallel;

    @Schema(title = "Supplement-data run type, as reported by DataArts Factory")
    private final Integer type;

    @Schema(title = "User who submitted the run")
    private final String userName;
}
