package io.kestra.plugin.huawei.eventgrid;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.eg.v1.model.CloudEvents;
import com.huaweicloud.sdk.eg.v1.model.PutEventsReq;
import com.huaweicloud.sdk.eg.v1.model.PutEventsRequest;
import com.huaweicloud.sdk.eg.v1.model.PutEventsRespEvents;
import com.huaweicloud.sdk.eg.v1.model.PutEventsResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.flows.State;
import io.kestra.core.models.property.Data;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.Value;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Publish events to Huawei Cloud EventGrid (EG)",
    description = """
        Publishes one or more CloudEvents-1.0-formatted events to an EventGrid custom channel via
        `putEvents` — the Huawei Cloud equivalent of `io.kestra.plugin.aws.eventbridge.PutEvents`.

        `events` accepts a single event, an inline list of events, or a URI pointing to serialized events
        in Kestra internal storage (e.g. produced by a previous task), following the same structured-data
        conventions as `io.kestra.plugin.huawei.dms.kafka.Produce#from`. Every event requires `source` and
        `type`; `id` is auto-generated and `specversion` defaults to `1.0` when omitted.

        EventGrid's per-request batch size cap is not documented, so events are sent in batches of at
        most 10 (matching the AWS EventBridge equivalent) rather than in one unbounded request; the
        per-event results are aggregated back together by submission order.

        Authentication uses AK/SK request signing. Provide `accessKeyId` and `secretAccessKey` via
        `{{ secret('NAME') }}`, or configure `temporaryCredentials` for inline IAM credential exchange.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Publish an order-created event to an EventGrid channel.",
            full = true,
            code = """
                id: eventgrid_put_events
                namespace: company.team

                tasks:
                  - id: put_events
                    type: io.kestra.plugin.huawei.eventgrid.PutEvents
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    channelId: "{{ secret('HUAWEI_EG_CHANNEL_ID') }}"
                    events:
                      - source: my-order-service
                        type: com.mycompany.order.created
                        subject: "order/12345"
                        data:
                          orderId: 12345
                          amount: 42.50
                """
        )
    },
    metrics = {
        @Metric(name = "eventgrid.putevents.count", type = Counter.TYPE, unit = "events",
            description = "Number of events successfully accepted by EventGrid.")
    }
)
public class PutEvents extends AbstractEventGrid implements RunnableTask<PutEvents.Output> {

    // EventGrid's per-request cap is undocumented, so — like ces.Push and the AWS EventBridge PutEvents this
    // task mirrors — events are sent in batches of at most this many, and the per-event results are aggregated
    // back together by submission order. This also bounds the size of any single request built in memory.
    private static final int MAX_BATCH_SIZE = 10;

    @Schema(title = "EventGrid channel ID", description = "The ID of the EventGrid custom channel to publish events to.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> channelId;

    @Schema(
        title = "Events to publish",
        description = """
            A single event, a list of events, or a URI pointing to serialized events in Kestra internal
            storage (e.g. produced by a previous task). Every event requires `source` and `type` per the
            CloudEvents 1.0 specification.
            """,
        oneOf = { String.class, Event[].class }
    )
    @PluginProperty(group = "main")
    @NotNull
    private Object events;

    @Schema(
        title = "Fail when EventGrid rejects at least one event",
        description = """
            If `true` (default), the task throws when `putEvents` reports one or more failed events.
            Set to `false` to inspect the per-event results in the output instead — the task then
            completes with a `WARNING` state when at least one event was rejected.
            """
    )
    @Builder.Default
    @PluginProperty(group = "reliability")
    private Property<Boolean> failOnUnsuccessfulEvents = Property.ofValue(true);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rChannelId = runContext.render(channelId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("channelId is required"));
        var rFailOnUnsuccessful = runContext.render(failOnUnsuccessfulEvents).as(Boolean.class).orElse(true);

        var eventList = readEvents(runContext, events);
        if (eventList.isEmpty()) {
            throw new IllegalArgumentException("'events' must contain at least one event");
        }

        var cloudEvents = new ArrayList<CloudEvents>(eventList.size());
        for (var i = 0; i < eventList.size(); i++) {
            cloudEvents.add(toCloudEvent(runContext, eventList.get(i), i));
        }

        var client = client(runContext);

        // Send in bounded batches and concatenate the per-batch results in order. EventGrid returns one result
        // per submitted event in submission order, so the flat concatenation stays index-aligned with cloudEvents.
        var results = new ArrayList<PutEventsRespEvents>(cloudEvents.size());
        var failedCount = 0;
        for (var chunk : partition(cloudEvents, MAX_BATCH_SIZE)) {
            var request = new PutEventsRequest()
                .withChannelId(rChannelId)
                .withBody(new PutEventsReq().withEvents(chunk));

            PutEventsResponse response;
            try {
                response = client.putEvents(request);
            } catch (ServiceResponseException e) {
                throw new IllegalStateException(
                    "EventGrid PutEvents failed (HTTP " + e.getHttpStatusCode() + "): " + e.getErrorMsg() +
                    " — verify the channelId and that the AK/SK has 'EG FullAccess' permission.", e);
            } catch (SdkException e) {
                throw new IllegalStateException("EventGrid SDK error publishing events: " + e.getMessage(), e);
            }

            var chunkResults = response.getEvents() != null ? response.getEvents() : List.<PutEventsRespEvents>of();
            if (chunkResults.size() != chunk.size()) {
                runContext.logger().warn(
                    "EventGrid returned {} result(s) for a batch of {} submitted event(s); per-event results are " +
                    "matched by submission order and may be misaligned.", chunkResults.size(), chunk.size());
            }
            results.addAll(chunkResults);
            failedCount += response.getFailedCount() != null ? response.getFailedCount() : 0;
        }

        var uri = runContext.storage().putFile(writeResults(runContext, eventList.size(), results));

        runContext.metric(Counter.of("eventgrid.putevents.count", eventList.size() - failedCount));
        runContext.logger().info(
            "Published {} event(s) to EventGrid channel '{}', {} failed",
            eventList.size(), rChannelId, failedCount);

        if (rFailOnUnsuccessful && failedCount > 0) {
            throw new IllegalStateException(
                "EventGrid rejected " + failedCount + " of " + eventList.size() + " event(s): " +
                describeFailures(results));
        }

        return Output.builder()
            .uri(uri)
            .eventCount(eventList.size())
            .failedEventCount(failedCount)
            .build();
    }

    // The event maps arrive from Data un-rendered (see readEvents), so every field is rendered here exactly once.
    // Rendering must NOT happen a second time anywhere downstream: a value that a first render resolves to another
    // Pebble expression (e.g. an input whose value is literally `{{ secret('X') }}`) would otherwise be evaluated,
    // leaking the resolved value into the published event.
    @SuppressWarnings("unchecked")
    private static CloudEvents toCloudEvent(RunContext runContext, Map<String, Object> event, int index) {
        try {
            var rId = renderScalar(runContext, event.get("id"));
            var rSource = renderScalar(runContext, event.get("source"));
            if (rSource == null || rSource.isBlank()) {
                throw new IllegalArgumentException("events[" + index + "].source is required");
            }
            var rType = renderScalar(runContext, event.get("type"));
            if (rType == null || rType.isBlank()) {
                throw new IllegalArgumentException("events[" + index + "].type is required");
            }
            var rSpecversion = renderScalar(runContext, event.get("specversion"));
            var rSubject = renderScalar(runContext, event.get("subject"));
            var rTime = renderScalar(runContext, event.get("time"));
            var rDatacontenttype = renderScalar(runContext, event.get("datacontenttype"));
            var rDataschema = renderScalar(runContext, event.get("dataschema"));

            Map<String, Object> rData = null;
            if (event.get("data") instanceof Map<?, ?> dataMap) {
                rData = runContext.render((Map<String, Object>) dataMap);
            }

            var cloudEvent = new CloudEvents()
                .withId(rId != null && !rId.isBlank() ? rId : UUID.randomUUID().toString())
                .withSource(rSource)
                .withType(rType)
                .withSpecversion(rSpecversion != null && !rSpecversion.isBlank() ? rSpecversion : "1.0")
                .withTime(rTime != null && !rTime.isBlank() ? rTime : Instant.now().toString());
            if (rData != null && !rData.isEmpty()) {
                cloudEvent.withData(rData);
            }
            if (rSubject != null) {
                cloudEvent.withSubject(rSubject);
            }
            if (rDatacontenttype != null) {
                cloudEvent.withDatacontenttype(rDatacontenttype);
            } else if (rData != null && !rData.isEmpty()) {
                cloudEvent.withDatacontenttype("application/json");
            }
            if (rDataschema != null) {
                cloudEvent.withDataschema(rDataschema);
            }
            return cloudEvent;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid event at index " + index + ": " + e.getMessage(), e);
        }
    }

    private static String renderScalar(RunContext runContext, Object value) throws Exception {
        return value == null ? null : runContext.render(value.toString());
    }

    private static File writeResults(RunContext runContext, int eventCount, List<PutEventsRespEvents> responseEvents) throws Exception {
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
            for (var i = 0; i < eventCount; i++) {
                var respEvent = i < responseEvents.size() ? responseEvents.get(i) : null;
                var result = EventResult.builder()
                    .index(i)
                    .eventId(respEvent != null ? respEvent.getEventId() : null)
                    .errorCode(respEvent != null ? respEvent.getErrorCode() : null)
                    .errorMsg(respEvent != null ? respEvent.getErrorMsg() : null)
                    .build();
                FileSerde.write(output, result);
            }
            output.flush();
        }
        return tempFile;
    }

    // Cap the number of failures listed so a large batch with a high failure rate can't produce a huge
    // exception message; the full per-event breakdown is always available in the results file.
    private static final int MAX_REPORTED_FAILURES = 20;

    private static String describeFailures(List<PutEventsRespEvents> responseEvents) {
        var failures = new ArrayList<String>();
        var total = 0;
        for (var i = 0; i < responseEvents.size(); i++) {
            var respEvent = responseEvents.get(i);
            if (respEvent.getErrorCode() != null) {
                total++;
                if (failures.size() < MAX_REPORTED_FAILURES) {
                    failures.add("index " + i + ": " + respEvent.getErrorCode() + " - " + respEvent.getErrorMsg());
                }
            }
        }
        var summary = String.join("; ", failures);
        if (total > failures.size()) {
            summary += "; ... and " + (total - failures.size()) + " more";
        }
        return summary;
    }

    private static List<Map<String, Object>> readEvents(RunContext runContext, Object events) throws Exception {
        // Normalize a single inline event (a Map) into a one-element list so Data takes its list branch, which
        // returns the raw maps un-rendered — a single inline Map would otherwise be pre-rendered by Data, and the
        // per-field render in toCloudEvent would then be a second render. All shapes (inline list, storage URI,
        // JSON string) thus reach toCloudEvent un-rendered, keeping rendering to exactly one pass.
        var normalized = events instanceof Map ? List.of(events) : events;
        return Data.from(normalized)
            .read(runContext)
            .collectList()
            .block();
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        return IntStream.range(0, (items.size() + size - 1) / size)
            .mapToObj(i -> items.subList(i * size, Math.min(items.size(), (i + 1) * size)))
            .toList();
    }

    @Value
    @Builder
    @Jacksonized
    public static class Event {

        @Schema(
            title = "Event ID",
            description = "Unique identifier for the event (CloudEvents `id`). Auto-generated as a random UUID when omitted."
        )
        @PluginProperty(group = "advanced")
        Property<String> id;

        @Schema(
            title = "Event source",
            description = "Identifies the context that produced the event (CloudEvents `source`), e.g. a service or application name."
        )
        @NotNull
        @PluginProperty(group = "main")
        Property<String> source;

        @Schema(
            title = "Event type",
            description = "Describes the kind of event (CloudEvents `type`), e.g. `com.mycompany.order.created`."
        )
        @NotNull
        @PluginProperty(group = "main")
        Property<String> type;

        @Schema(
            title = "CloudEvents spec version",
            description = "Defaults to `1.0`, the only version currently defined by the CloudEvents specification."
        )
        @Builder.Default
        @PluginProperty(group = "advanced")
        Property<String> specversion = Property.ofValue("1.0");

        @Schema(title = "Event payload", description = "The event data, as a JSON object (CloudEvents `data`). Optional.")
        @PluginProperty(group = "main")
        Property<Map<String, Object>> data;

        @Schema(
            title = "Subject",
            description = "Describes the subject of the event in the context of its source (CloudEvents `subject`). Optional."
        )
        @PluginProperty(group = "advanced")
        Property<String> subject;

        @Schema(
            title = "Event timestamp",
            description = "ISO-8601/RFC 3339 timestamp of when the event occurred (CloudEvents `time`). Defaults to the current time when omitted."
        )
        @PluginProperty(group = "advanced")
        Property<String> time;

        @Schema(
            title = "Data content type",
            description = "Content type of `data` (CloudEvents `datacontenttype`), e.g. `application/json`. Defaults to `application/json` when `data` is set and this is omitted."
        )
        @PluginProperty(group = "advanced")
        Property<String> datacontenttype;

        @Schema(
            title = "Data schema",
            description = "URI identifying the schema that `data` adheres to (CloudEvents `dataschema`). Optional."
        )
        @PluginProperty(group = "advanced")
        Property<String> dataschema;
    }

    @Builder
    @Getter
    public static class EventResult {

        @Schema(title = "Index of the event in the submitted list")
        private final int index;

        @Schema(title = "EventGrid-assigned event ID", description = "Populated only when the event was accepted.")
        private final String eventId;

        @Schema(title = "Error code", description = "Populated only when EventGrid rejected the event.")
        private final String errorCode;

        @Schema(title = "Error message", description = "Populated only when EventGrid rejected the event.")
        private final String errorMsg;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "URI of the ION file in Kestra internal storage containing per-event results",
            description = "Each entry has `index`, `eventId` (when accepted), and `errorCode`/`errorMsg` (when rejected)."
        )
        private final URI uri;

        @Schema(title = "Total number of events submitted")
        private final int eventCount;

        @Schema(title = "Number of events EventGrid rejected")
        private final int failedEventCount;

        @Override
        public Optional<State.Type> finalState() {
            return failedEventCount > 0 ? Optional.of(State.Type.WARNING) : io.kestra.core.models.tasks.Output.super.finalState();
        }
    }
}
