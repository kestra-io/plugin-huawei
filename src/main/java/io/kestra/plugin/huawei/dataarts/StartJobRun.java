package io.kestra.plugin.huawei.dataarts;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
import io.kestra.plugin.huawei.dataarts.models.SupplementDataRun;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Start a DataArts Studio (DataArts Factory) job run",
    description = """
        Triggers an on-demand run of a batch job in Huawei Cloud DataArts Factory (DLF) by creating a
        supplement-data (PatchData) instance via `POST /v2/{project_id}/factory/supplement-data`, then
        optionally polling until it completes.

        **Why supplement-data**: DataArts Factory publishes no usable job-trigger API. Both
        `jobs/{job_name}/run-immediate` and `jobs/{job_name}/start` reject every request with
        `DLF.3051`, on standard and sovereign regions alike, and neither route appears in the Huawei
        SDK's own route metadata. Supplement-data is the only declared mechanism that creates a run,
        and it comes with matching status and stop routes.

        **Requirement — the job must have a trigger**: supplement-data only accepts a job configured
        with a cron schedule, an HTTP trigger, or a parent job. A run-once / manually-triggered job is
        rejected with `DLF.30111`. The schedule does not have to fire on its own — it only has to
        exist. Since DataArts publishes no other working job-trigger route, giving the job a schedule
        in the console is a prerequisite for driving it from Kestra.

        **What this means in practice**: supplement-data is a *backfill* — it runs the job over a
        range of business dates (`startDate` to `endDate`, defaulting to today). The run itself is
        identified by the `runName` you choose rather than by a numeric instance ID, and appears in
        the console's **Supplement Data** view; the job instances it spawns appear under **Monitor
        Instance**, named `P_<jobName>_<timestamp>`.

        **Sizing the range and `maxDuration`**: one instance is created per scheduling period of the
        job that falls inside the range, and at `parallel: 1` they run one after another. An hourly
        job over the default single-day range therefore means 24 sequential instances — minutes of
        wall time even when each instance takes seconds. To run the job just once, set the range to a
        single scheduling period (one hour for an hourly job); to speed up a real backfill, raise
        `parallel` (max 5).

        Use `StopJobRun` with the same `runName` to cancel a run before it completes. `GetJobRun`
        reports on plain job instances (including console-triggered ones) and is not tied to this
        task.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Run a single instance of a DataArts Factory job and wait for it to complete. " +
                "The range covers one scheduling period of the job — one hour here — so exactly one " +
                "instance is created. Leaving the range at its default spans a whole day, which for " +
                "an hourly job means 24 instances run one after another.",
            full = true,
            code = """
                id: dataarts_start_job
                namespace: company.team

                tasks:
                  - id: start_job
                    type: io.kestra.plugin.huawei.dataarts.StartJobRun
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    workspaceId: "{{ secret('HUAWEI_WORKSPACE_ID') }}"
                    jobName: my_etl_job
                    startDate: "{{ now() | date('yyyy-MM-dd HH:00:00') }}"
                    endDate: "{{ now() | dateAdd(1, 'HOURS') | date('yyyy-MM-dd HH:00:00') }}"
                    wait: true
                    maxDuration: PT10M
                """
        ),
        @Example(
            title = "Run a DataArts Factory job for the whole of today's business date. Every " +
                "scheduling period in the range produces its own instance, so size maxDuration for " +
                "the total rather than for one instance.",
            full = true,
            code = """
                id: dataarts_start_job_today
                namespace: company.team

                tasks:
                  - id: start_job
                    type: io.kestra.plugin.huawei.dataarts.StartJobRun
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    workspaceId: "{{ secret('HUAWEI_WORKSPACE_ID') }}"
                    jobName: my_etl_job
                    wait: true
                    maxDuration: PT30M
                """
        ),
        @Example(
            title = "Backfill a job over a date range, four instances at a time, without waiting.",
            full = true,
            code = """
                id: dataarts_backfill
                namespace: company.team

                tasks:
                  - id: backfill
                    type: io.kestra.plugin.huawei.dataarts.StartJobRun
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    projectId: "{{ secret('HUAWEI_PROJECT_ID') }}"
                    workspaceId: "{{ secret('HUAWEI_WORKSPACE_ID') }}"
                    jobName: my_etl_job
                    runName: backfill_july
                    startDate: "2026-07-01"
                    endDate: "2026-07-31"
                    parallel: 4
                    stopWhenFail: false
                    wait: false
                """
        )
    }
)
public class StartJobRun extends AbstractDataArts implements RunnableTask<StartJobRun.Output> {

    /**
     * The wire form DataArts requires, per the example in its own API reference for
     * <a href="https://support.huaweicloud.com/intl/en-us/api-dataartsstudio/dataartsstudio_02_0199.html">
     * Creating a PatchData Instance</a>: {@code 2023-08-21T00:00:00 +08} — an ISO date-time with a
     * {@code T} separator, then a <em>space</em>, then a UTC offset.
     *
     * <p>The backend silently fails to parse anything else and then compares two identical values,
     * so every unrecognised spelling is reported as {@code DLF.30121} ("The end time should be at
     * least 2 second later than the start time") no matter how wide the requested range is. Verified
     * live on ap-southeast-3: {@code yyyy-MM-dd}, {@code yyyy-MM-dd HH:mm:ss} and epoch
     * milliseconds all produce that same error, including for a range spanning a whole day.
     */
    private static final DateTimeFormatter WIRE_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Offset appended to values that don't carry one. Times without an offset are read as UTC. */
    private static final String UTC_OFFSET = "+00";

    /**
     * Matches a trailing UTC offset, but only when a {@code HH:mm} time precedes it — otherwise the
     * {@code -28} in a bare {@code 2026-07-28} would look like an offset.
     */
    private static final Pattern EXPLICIT_OFFSET =
        Pattern.compile("\\d{2}:\\d{2}(?::\\d{2})?\\s*(?:[+-]\\d{2}(?::?\\d{2})?|Z)$");

    private static final int DATE_ONLY_LENGTH = "yyyy-MM-dd".length();

    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    @Schema(
        title = "Name of the DataArts Factory job to run",
        description = "Must match the job name exactly as defined in the DataArts Studio console."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> jobName;

    @Schema(
        title = "Name to give the supplement-data run",
        description = """
            Identifies the run for status polling and for `StopJobRun`. Must be unique within the
            workspace — reusing a name conflicts with the existing run. When omitted, a unique name
            is generated as `kestra_<jobName>_<random>`.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> runName;

    @Schema(
        title = "Start of the business-date range to run the job for",
        description = """
            Accepts `yyyy-MM-dd` (start of that day), `yyyy-MM-dd HH:mm:ss`, or an ISO date-time.
            Times without a UTC offset are read as UTC. Defaults to the start of today (UTC).

            DataArts itself requires the form `2026-07-28T00:00:00 +00` and does not parse any other
            spelling, so the value is converted for you. A value that already ends in a UTC offset is
            sent through verbatim, which is the escape hatch if the required form ever changes.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> startDate;

    @Schema(
        title = "End of the business-date range to run the job for",
        description = """
            Accepts the same formats as `startDate`, except that a date-only value means the *end* of
            that day (`23:59:59`), so `startDate: 2026-07-01` with `endDate: 2026-07-31` covers all of
            July. Defaults to the end of the day `startDate` falls on, making the default a single-day
            run — the equivalent of a plain "run now".

            Must be at least two seconds after `startDate`: DataArts compares the two as timestamps
            and rejects a zero-length or inverted range with `DLF.30121`.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> endDate;

    @Schema(
        title = "Number of instances to run concurrently",
        description = "Only meaningful when the date range spans more than one instance. Defaults to 1."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Integer> parallel = Property.ofValue(1);

    @Schema(
        title = "Treat the date range as day-granular",
        description = """
            When `true` (the default), one instance is created per day in the range. Set to `false`
            for jobs scheduled at a finer granularity, where the range should follow the job's own
            schedule instead.
            """
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> dayGranularity = Property.ofValue(true);

    @Schema(
        title = "Abort the remaining instances if one fails",
        description = "When `true` (the default), a failed instance stops the rest of the range from running."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> stopWhenFail = Property.ofValue(true);

    @Schema(
        title = "Daily window the run is allowed to execute in, as `HH:mm-HH:mm`",
        description = """
            Omitted by default, which lets DataArts run the backfill immediately — its documented
            default of `00:00-00:00` does not restrict execution, verified live.

            Set this only to confine a backfill to off-peak hours, e.g. `01:00-05:00`.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> runTimeWindow;

    @Schema(
        title = "Wait for the run to reach a terminal state",
        description = """
            When `true` (the default), the task polls the run's status until it completes, then fails
            the Kestra task if the run did not succeed. Set to `false` to return as soon as the run
            has been accepted and is visible.
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Boolean> wait = Property.ofValue(true);

    @Schema(
        title = "Maximum time to wait for the run to complete",
        description = """
            ISO-8601 duration (e.g. `PT30M`, `PT1H`). Bounds both the wait for the run to become
            visible and the status polling that follows. When the deadline passes before the run
            reaches a terminal state, the task fails with a timeout error. Defaults to 1 hour.
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> maxDuration = Property.ofValue(Duration.ofHours(1));

    @Schema(
        title = "Polling interval while waiting for the run to complete",
        description = "ISO-8601 duration (e.g. `PT5S`). Defaults to 5 seconds."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Duration> interval = Property.ofValue(Duration.ofSeconds(5));

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rJobName = runContext.render(jobName).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("jobName is required"));
        var rProjectId = resolvedProjectId(runContext);
        var rEndpoint = resolvedEndpoint(runContext);
        var rWorkspaceId = resolvedWorkspaceId(runContext);
        var rWait = runContext.render(wait).as(Boolean.class).orElse(true);
        var rMaxDuration = runContext.render(maxDuration).as(Duration.class).orElse(Duration.ofHours(1));
        var rInterval = runContext.render(interval).as(Duration.class).orElse(Duration.ofSeconds(5));
        var rParallel = runContext.render(parallel).as(Integer.class).orElse(1);
        var rDayGranularity = runContext.render(dayGranularity).as(Boolean.class).orElse(true);
        var rStopWhenFail = runContext.render(stopWhenFail).as(Boolean.class).orElse(true);
        var rRunTimeWindow = runContext.render(runTimeWindow).as(String.class).orElse(null);

        var rRunName = runContext.render(runName).as(String.class)
            .orElseGet(() -> generateRunName(rJobName));
        // Both ends are normalised to the one form DataArts parses, and default to a whole day
        // rather than a single instant: the two are compared as timestamps and a zero-length range
        // is rejected with DLF.30121 ("The end time should be at least 2 second later than the
        // start time").
        var rawStartDate = runContext.render(startDate).as(String.class)
            .orElseGet(() -> LocalDate.now(ZoneOffset.UTC).toString());
        var rStartDate = toWireDateTime(rawStartDate, false, "startDate");
        var rEndDate = toWireDateTime(
            runContext.render(endDate).as(String.class).orElseGet(() -> sameDayAs(rawStartDate)),
            true,
            "endDate");

        var config = huaweiClientConfig(runContext);

        runContext.logger().info(
            "Starting DataArts Factory job '{}' as supplement-data run '{}' over {} → {} (run window {})",
            rJobName, rRunName, rStartDate, rEndDate, rRunTimeWindow);

        DataArtsService.createSupplementData(
            runContext, config, rEndpoint, rProjectId, rWorkspaceId,
            rRunName, rJobName, rStartDate, rEndDate, rParallel, rDayGranularity, rStopWhenFail,
            rRunTimeWindow);

        // The create response carries only a request ID, so the run has to be read back by name.
        // Bounded by the same deadline as the status polling below, so both together respect
        // maxDuration.
        var deadline = System.currentTimeMillis() + rMaxDuration.toMillis();
        var current = awaitVisible(runContext, config, rEndpoint, rProjectId, rWorkspaceId,
            rRunName, rInterval, deadline);

        runContext.logger().info("Supplement-data run '{}' created, status={}", rRunName, current.getStatus());

        if (!rWait) {
            return buildOutput(rJobName, current);
        }

        while (!DataArtsService.isSupplementDataTerminalState(current.getStatus())) {
            try {
                Thread.sleep(rInterval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "DataArts Factory supplement-data run '" + rRunName + "' for job '" + rJobName +
                    "' did not reach a terminal state within " + rMaxDuration +
                    " — last status: " + current.getStatus() +
                    ". Use StopJobRun with runName '" + rRunName + "' to cancel it, or increase maxDuration.");
            }
            var refreshed = DataArtsService.getSupplementData(
                runContext, config, rEndpoint, rProjectId, rWorkspaceId, rRunName);
            if (refreshed != null) {
                current = refreshed;
            }
            // Logged at INFO, not DEBUG: the supplement-data status vocabulary is undocumented, so
            // these lines are how the real values get discovered from a run's logs.
            runContext.logger().info("Supplement-data run '{}' status={}", rRunName, current.getStatus());
        }

        runContext.logger().info("Supplement-data run '{}' finished with status={}", rRunName, current.getStatus());

        if (!DataArtsService.isSupplementDataSuccessState(current.getStatus())) {
            throw new IllegalStateException(
                "DataArts Factory supplement-data run '" + rRunName + "' for job '" + rJobName +
                "' finished with status '" + current.getStatus() + "'" +
                " — check the run in the DataArts Studio console's Supplement Data view for details.");
        }

        return buildOutput(rJobName, current);
    }

    /**
     * Polls until the newly created run is readable by name.
     *
     * <p>Creation is asynchronous, so the run is not guaranteed to appear in the list immediately.
     * A missing row is therefore retried rather than treated as an error.
     */
    private SupplementDataRun awaitVisible(
        RunContext runContext,
        AbstractConnection.HuaweiClientConfig config,
        String endpoint, String projectId, String workspaceId,
        String runName, Duration interval, long deadline
    ) throws Exception {
        int attempt = 0;
        while (true) {
            var run = DataArtsService.getSupplementData(runContext, config, endpoint, projectId, workspaceId, runName);
            if (run != null) {
                return run;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                    "DataArts Factory supplement-data run '" + runName + "' was accepted but never appeared" +
                    " in the run list — it may have been rejected asynchronously. Check the DataArts Studio" +
                    " console's Supplement Data view, or increase maxDuration.");
            }
            attempt++;
            runContext.logger().debug("Supplement-data run '{}' not yet visible, waiting {} (attempt {})",
                runName, interval, attempt);
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
        }
    }

    /**
     * Converts a user-supplied date or date-time to the {@code 2026-07-28T00:00:00 +00} form
     * DataArts requires. A date-only value is widened to the start of that day, or to
     * {@code 23:59:59} when it is the end of the range, so {@code 2026-07-01} → {@code 2026-07-31}
     * covers all of July rather than stopping at midnight on the 31st.
     *
     * <p>A value that already carries a UTC offset is passed through untouched: it is either already
     * in the required form, or a deliberate attempt to reach a form this method doesn't produce.
     */
    private static String toWireDateTime(String raw, boolean endOfDay, String property) {
        var trimmed = raw.trim();
        if (EXPLICIT_OFFSET.matcher(trimmed).find()) {
            return trimmed;
        }

        try {
            var dateTime = trimmed.length() == DATE_ONLY_LENGTH
                ? LocalDate.parse(trimmed).atTime(endOfDay ? END_OF_DAY : LocalTime.MIDNIGHT)
                // ISO parsing covers both 'T' and the space-separated spelling once normalised.
                : LocalDateTime.parse(trimmed.replace(' ', 'T'));
            return dateTime.format(WIRE_DATE_TIME) + " " + UTC_OFFSET;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                property + " ('" + raw + "') is not a date or date-time. Use 'yyyy-MM-dd'," +
                " 'yyyy-MM-dd HH:mm:ss', or DataArts' own form 'yyyy-MM-ddTHH:mm:ss +00'." +
                " Epoch milliseconds are not accepted — DataArts does not parse them either.");
        }
    }

    /**
     * The calendar day {@code startDate} falls on, so an omitted {@code endDate} spans that whole day
     * instead of producing a zero-length range (which DataArts rejects with {@code DLF.30121}).
     *
     * <p>Only the leading {@code yyyy-MM-dd} is needed, which every accepted spelling of
     * {@code startDate} begins with — including the offset-bearing form passed through verbatim.
     */
    private static String sameDayAs(String startDate) {
        var trimmed = startDate.trim();
        if (trimmed.length() >= DATE_ONLY_LENGTH) {
            try {
                return LocalDate.parse(trimmed.substring(0, DATE_ONLY_LENGTH)).toString();
            } catch (DateTimeParseException ignored) {
                // Fall through to the shared error below.
            }
        }
        throw new IllegalArgumentException(
            "endDate is required when startDate ('" + startDate + "') does not begin with a" +
            " 'yyyy-MM-dd' date — the end of the range cannot be derived from it, and DataArts needs" +
            " endDate to be at least 2 seconds after startDate (DLF.30121).");
    }

    /**
     * Builds a workspace-unique default run name. DataArts rejects a name that collides with an
     * existing run, so a random suffix is appended rather than relying on the job name alone.
     */
    private static String generateRunName(String jobName) {
        var sanitized = jobName.replaceAll("[^a-zA-Z0-9_]", "_");
        // Keep well inside any plausible server-side length cap while staying recognisable.
        if (sanitized.length() > 40) {
            sanitized = sanitized.substring(0, 40);
        }
        return "kestra_" + sanitized + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Output buildOutput(String jobName, SupplementDataRun run) {
        return Output.builder()
            .jobName(jobName)
            .runName(run.getName())
            .status(run.getStatus())
            .jobList(run.getJobList())
            .startDate(run.getStartDate())
            .endDate(run.getEndDate())
            .submittedDate(run.getSubmittedDate())
            .parallel(run.getParallel())
            .userName(run.getUserName())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Job name")
        private final String jobName;

        @Schema(
            title = "Name of the supplement-data run",
            description = "Pass this to `StopJobRun` to cancel the run."
        )
        private final String runName;

        @Schema(title = "Status of the run when the task returned")
        private final String status;

        @Schema(title = "Jobs covered by the run")
        private final List<String> jobList;

        @Schema(title = "Start of the covered business-date range (epoch milliseconds)")
        private final Long startDate;

        @Schema(title = "End of the covered business-date range (epoch milliseconds)")
        private final Long endDate;

        @Schema(title = "Time the run was submitted (epoch milliseconds)")
        private final Long submittedDate;

        @Schema(title = "Number of instances executed in parallel")
        private final Integer parallel;

        @Schema(title = "User the run was submitted as")
        private final String userName;
    }
}
