package io.kestra.plugin.huawei.ces;

import com.huaweicloud.sdk.ces.v1.CesClient;
import com.huaweicloud.sdk.ces.v1.model.Datapoint;
import com.huaweicloud.sdk.ces.v1.model.ShowMetricDataRequest;
import com.huaweicloud.sdk.ces.v1.model.ShowMetricDataResponse;
import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Query metric statistics from Huawei Cloud CES (Cloud Eye Service)",
    description = """
        Queries a single metric's datapoints over a time window, aggregated at a given `period` using
        the requested `statistic`. Works for both system metrics (e.g. `SYS.ECS`) and custom metrics
        pushed with the `Push` task.

        Authentication uses AK/SK request signing. Provide `accessKeyId` and `secretAccessKey` via
        `{{ secret('NAME') }}`, or configure `temporaryCredentials` for inline IAM credential exchange.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Query the average CPU utilization of an ECS instance over the last hour.",
            full = true,
            code = """
                id: ces_query
                namespace: company.team

                tasks:
                  - id: query
                    type: io.kestra.plugin.huawei.ces.Query
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    namespace: SYS.ECS
                    metricName: cpu_util
                    dimensions:
                      - name: instance_id
                        value: "abc123def456"
                    statistic: AVERAGE
                    period: FIVE_MINUTES
                    window: PT1H
                """
        )
    }
)
public class Query extends AbstractCes implements RunnableTask<Query.Output> {

    // Bounds memory usage when period=RAW combined with a large window; keeps the most recent points.
    static final int MAX_SERIES_SIZE = 1440;

    // Bounds the queried time range so the SDK never has to deserialize an oversized response
    // before MAX_SERIES_SIZE truncation applies.
    public static final Duration MAX_WINDOW = Duration.ofDays(30);

    @Schema(
        title = "Metric namespace",
        description = """
            The `service.item` namespace the metric belongs to, e.g. `SYS.ECS` for system-level ECS
            metrics, or a custom namespace such as `MyApp.Custom` for metrics pushed with `Push`.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> namespace;

    @Schema(title = "Metric name", description = "E.g. `cpu_util` for the ECS CPU utilization metric.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> metricName;

    @Schema(
        title = "Dimensions identifying the monitored resource",
        description = "1 to 3 name/value pairs, e.g. `instance_id` for an ECS instance. CES requires at least one dimension to identify the exact resource instance."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<Dimension>> dimensions;

    @Schema(
        title = "Statistic to aggregate datapoints with",
        description = "Defaults to `AVERAGE`."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Statistic> statistic = Property.ofValue(Statistic.AVERAGE);

    @Schema(
        title = "Aggregation period",
        description = "Defaults to `FIVE_MINUTES`."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Period> period = Property.ofValue(Period.FIVE_MINUTES);

    @Schema(
        title = "Time window to query, ending now",
        description = "How far back from the current time to query datapoints for, as an ISO-8601 duration. Defaults to `PT1H`. Maximum: 30 days (`P30D`)."
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<Duration> window = Property.ofValue(Duration.ofHours(1));

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rNamespace = runContext.render(namespace).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("namespace is required"));
        CesUtils.validateNamespaceFormat(rNamespace);

        var rMetricName = runContext.render(metricName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("metricName is required"));
        var rDimensions = runContext.render(dimensions).asList(Dimension.class);
        if (rDimensions.isEmpty() || rDimensions.size() > 3) {
            throw new IllegalArgumentException(
                "CES requires between 1 and 3 dimensions per metric query, but " + rDimensions.size() + " were provided.");
        }
        var rStatistic = runContext.render(statistic).as(Statistic.class).orElse(Statistic.AVERAGE);
        var rPeriod = runContext.render(period).as(Period.class).orElse(Period.FIVE_MINUTES);
        var rWindow = runContext.render(window).as(Duration.class).orElse(Duration.ofHours(1));
        if (rWindow.isZero() || rWindow.isNegative()) {
            throw new IllegalArgumentException("window must be a positive duration, but was " + rWindow + ".");
        }
        if (rWindow.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("window must not exceed " + MAX_WINDOW + " (P30D), but was " + rWindow + " — narrow the window or query CES in smaller ranges.");
        }

        var now = Instant.now();
        var from = now.minus(rWindow).toEpochMilli();
        var to = now.toEpochMilli();

        var request = new ShowMetricDataRequest()
            .withNamespace(rNamespace)
            .withMetricName(rMetricName)
            .withFilter(rStatistic.toSdk())
            .withPeriod(rPeriod.toSdk())
            .withFrom(from)
            .withTo(to);

        for (var i = 0; i < rDimensions.size(); i++) {
            final var index = i;
            var rName = runContext.render(rDimensions.get(i).getName()).as(String.class)
                .orElseThrow(() -> new IllegalArgumentException("dimensions[" + index + "].name is required"));
            var rValue = runContext.render(rDimensions.get(i).getValue()).as(String.class)
                .orElseThrow(() -> new IllegalArgumentException("dimensions[" + index + "].value is required for dimension '" + rName + "'"));
            var dim = rName + "," + rValue;
            switch (i) {
                case 0 -> request.withDim0(dim);
                case 1 -> request.withDim1(dim);
                case 2 -> request.withDim2(dim);
                default -> throw new IllegalStateException("unreachable");
            }
        }

        runContext.logger().debug("Querying CES metric {}/{} from={} to={} period={} statistic={}",
            rNamespace, rMetricName, from, to, rPeriod, rStatistic);

        var client = client(runContext);

        var response = query(client, request, rNamespace, rMetricName);

        var series = response.getDatapoints() == null ? List.<Point>of() : response.getDatapoints().stream()
            .map(dp -> Point.builder()
                .timestamp(dp.getTimestamp() != null ? Instant.ofEpochMilli(dp.getTimestamp()) : null)
                .value(valueFor(dp, rStatistic))
                .unit(dp.getUnit())
                .build())
            .sorted(Comparator.comparing(Point::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        if (series.size() > MAX_SERIES_SIZE) {
            runContext.logger().warn(
                "CES query for {}/{} returned {} datapoints, keeping only the {} most recent — narrow 'window' or increase 'period' for finer-grained coverage over a longer range.",
                rNamespace, rMetricName, series.size(), MAX_SERIES_SIZE);
            series = series.subList(series.size() - MAX_SERIES_SIZE, series.size());
        }

        runContext.logger().info("CES query for {}/{} returned {} datapoints", rNamespace, rMetricName, series.size());

        return Output.builder()
            .count(series.size())
            .series(series)
            .build();
    }

    private static ShowMetricDataResponse query(
        CesClient client,
        ShowMetricDataRequest request,
        String namespace,
        String metricName
    ) {
        try {
            return client.showMetricData(request);
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "CES query for metric '" + namespace + "/" + metricName + "' failed (HTTP " + e.getHttpStatusCode() +
                "): " + e.getErrorMsg() + " — verify the namespace, metric name, and dimensions are correct, and " +
                "that the AK/SK has 'CES ReadOnlyAccess' permission.", e);
        } catch (SdkException e) {
            throw new IllegalStateException(
                "CES SDK error querying metric '" + namespace + "/" + metricName + "': " + e.getMessage(), e);
        }
    }

    private static Double valueFor(Datapoint datapoint, Statistic statistic) {
        return switch (statistic) {
            case AVERAGE -> datapoint.getAverage();
            case MAX -> datapoint.getMax();
            case MIN -> datapoint.getMin();
            case SUM -> datapoint.getSum();
            case VARIANCE -> datapoint.getVariance();
        };
    }

    public enum Statistic {
        AVERAGE,
        MAX,
        MIN,
        SUM,
        VARIANCE;

        ShowMetricDataRequest.FilterEnum toSdk() {
            return ShowMetricDataRequest.FilterEnum.fromValue(name().toLowerCase());
        }
    }

    public enum Period {
        RAW(1),
        ONE_MINUTE(60),
        FIVE_MINUTES(300),
        TWENTY_MINUTES(1200),
        ONE_HOUR(3600),
        FOUR_HOURS(14400),
        ONE_DAY(86400);

        private final int seconds;

        Period(int seconds) {
            this.seconds = seconds;
        }

        ShowMetricDataRequest.PeriodEnum toSdk() {
            return ShowMetricDataRequest.PeriodEnum.fromValue(seconds);
        }
    }

    @Builder
    @Getter
    public static class Point {

        @Schema(title = "Datapoint timestamp")
        private final Instant timestamp;

        @Schema(title = "Datapoint value for the requested statistic")
        private final Double value;

        @Schema(title = "Unit reported by CES for this datapoint", description = "E.g. `%`, `Count`, `Bytes`.")
        private final String unit;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Number of datapoints returned")
        private final Integer count;

        @Schema(
            title = "Datapoints sorted by timestamp ascending",
            description = "Capped at " + MAX_SERIES_SIZE + " datapoints (the most recent ones) to bound memory usage. " +
                "Narrow `window` or increase `period` if you need finer-grained coverage over a longer range."
        )
        private final List<Point> series;
    }
}
