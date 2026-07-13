package io.kestra.plugin.huawei.eventgrid;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
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
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

        `events` accepts either an inline list of events or a `kestra://` internal storage URI pointing
        to ION-serialized events (e.g. produced by a previous task). Every event requires `source` and
        `type`; `id` is auto-generated and `specversion` defaults to `1.0` when omitted.

        EventGrid's per-request batch size cap is not documented — this task always sends the whole
        `events` list in a single `putEvents` call and surfaces any size-related API error verbatim
        rather than guessing a safe chunk size.

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

    private static final ObjectMapper MAPPER = JacksonMapper.ofIon().setSerializationInclusion(JsonInclude.Include.ALWAYS);

    @Schema(title = "EventGrid channel ID", description = "The ID of the EventGrid custom channel to publish events to.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> channelId;

    @Schema(
        title = "Events to publish",
        description = """
            Either an inline list of events, or a `kestra://` internal storage URI pointing to
            ION-serialized events (e.g. produced by a previous task). Every event requires `source` and
            `type` per the CloudEvents 1.0 specification.
            """,
        oneOf = { String.class, Event[].class }
    )
    @PluginProperty(dynamic = true, group = "main")
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
        var request = new PutEventsRequest()
            .withChannelId(rChannelId)
            .withBody(new PutEventsReq().withEvents(cloudEvents));

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

        var failedCount = response.getFailedCount() != null ? response.getFailedCount() : 0;
        var uri = runContext.storage().putFile(writeResults(runContext, eventList, response));

        runContext.metric(Counter.of("eventgrid.putevents.count", eventList.size() - failedCount));
        runContext.logger().info(
            "Published {} event(s) to EventGrid channel '{}', {} failed",
            eventList.size(), rChannelId, failedCount);

        if (rFailOnUnsuccessful && failedCount > 0) {
            throw new IllegalStateException(
                "EventGrid rejected " + failedCount + " of " + eventList.size() + " event(s): " +
                describeFailures(response));
        }

        return Output.builder()
            .uri(uri)
            .eventCount(eventList.size())
            .failedEventCount(failedCount)
            .build();
    }

    private static CloudEvents toCloudEvent(RunContext runContext, Event event, int index) {
        try {
            var rId = runContext.render(event.getId()).as(String.class).orElseGet(() -> UUID.randomUUID().toString());
            var rSource = runContext.render(event.getSource()).as(String.class)
                .orElseThrow(() -> new IllegalArgumentException("events[" + index + "].source is required"));
            var rType = runContext.render(event.getType()).as(String.class)
                .orElseThrow(() -> new IllegalArgumentException("events[" + index + "].type is required"));
            var rSpecversion = runContext.render(event.getSpecversion()).as(String.class).orElse("1.0");
            var rData = runContext.render(event.getData()).asMap(String.class, Object.class);
            var rSubject = runContext.render(event.getSubject()).as(String.class).orElse(null);
            var rTime = runContext.render(event.getTime()).as(String.class).orElse(Instant.now().toString());
            var rDatacontenttype = runContext.render(event.getDatacontenttype()).as(String.class)
                .orElse(rData.isEmpty() ? null : "application/json");
            var rDataschema = runContext.render(event.getDataschema()).as(String.class).orElse(null);

            var cloudEvent = new CloudEvents()
                .withId(rId)
                .withSource(rSource)
                .withType(rType)
                .withSpecversion(rSpecversion)
                .withTime(rTime);
            if (!rData.isEmpty()) {
                cloudEvent.withData(rData);
            }
            if (rSubject != null) {
                cloudEvent.withSubject(rSubject);
            }
            if (rDatacontenttype != null) {
                cloudEvent.withDatacontenttype(rDatacontenttype);
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

    private static File writeResults(RunContext runContext, List<Event> eventList, PutEventsResponse response) throws Exception {
        var responseEvents = response.getEvents() != null ? response.getEvents() : List.<PutEventsRespEvents>of();
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
            for (var i = 0; i < eventList.size(); i++) {
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

    private static String describeFailures(PutEventsResponse response) {
        var responseEvents = response.getEvents() != null ? response.getEvents() : List.<PutEventsRespEvents>of();
        var sb = new StringBuilder();
        for (var i = 0; i < responseEvents.size(); i++) {
            var respEvent = responseEvents.get(i);
            if (respEvent.getErrorCode() != null) {
                sb.append("index ").append(i).append(": ").append(respEvent.getErrorCode())
                    .append(" - ").append(respEvent.getErrorMsg()).append("; ");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Event> readEvents(RunContext runContext, Object events) throws Exception {
        if (events instanceof String s) {
            var uri = new URI(runContext.render(s));
            if (!"kestra".equals(uri.getScheme())) {
                throw new IllegalArgumentException(
                    "'events' must be a Kestra internal storage URI (kestra://...) or a list of events.");
            }
            try (var reader = new InputStreamReader(runContext.storage().getFile(uri), StandardCharsets.UTF_8)) {
                return FileSerde.readAll(reader, Event.class).collectList().block();
            }
        } else if (events instanceof List<?> list) {
            return MAPPER.convertValue(list, new TypeReference<List<Event>>() {});
        }
        throw new IllegalArgumentException(
            "Invalid 'events' type '" + events.getClass() + "' — must be a list of events or a kestra:// URI.");
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
