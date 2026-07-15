package io.kestra.plugin.huawei.smn;

import com.huaweicloud.sdk.core.exception.SdkException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.smn.v2.model.MessageAttribute;
import com.huaweicloud.sdk.smn.v2.model.PublishMessageRequest;
import com.huaweicloud.sdk.smn.v2.model.PublishMessageRequestBody;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Publish a notification message to a Huawei Cloud SMN topic",
    description = """
        Publishes exactly one message to an [SMN (Simple Message Notification)](https://www.huaweicloud.com/product/smn.html)
        topic, the Huawei Cloud equivalent of `io.kestra.plugin.aws.sns.Publish`.

        Exactly one of `message`, `messageStructure`, or `messageTemplateName` must be set:

        - `message`: a plain-text body delivered to every subscription protocol as-is.
        - `messageStructure`: a map of protocol (`email`, `sms`, `http`, `https`, ...) to per-protocol
          body, letting each subscription protocol receive a tailored message.
        - `messageTemplateName`: publishes via a pre-created SMN message template, filling its
          placeholders from `tags`.

        Authentication uses AK/SK request signing. Provide `accessKeyId` and `secretAccessKey` via
        `{{ secret('NAME') }}`, or configure `temporaryCredentials` for inline IAM credential exchange.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Publish a plain-text notification to an SMN topic.",
            full = true,
            code = """
                id: smn_publish
                namespace: company.team

                tasks:
                  - id: publish
                    type: io.kestra.plugin.huawei.smn.Publish
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    topicUrn: "urn:smn:eu-west-101:0123456789abcdef0123456789abcdef:my-topic"
                    subject: "Flow completed"
                    message: "The flow {{ flow.id }} finished successfully."
                """
        ),
        @Example(
            title = "Publish a per-protocol message structure (email vs. SMS body) to an SMN topic.",
            full = true,
            code = """
                id: smn_publish_structured
                namespace: company.team

                tasks:
                  - id: publish
                    type: io.kestra.plugin.huawei.smn.Publish
                    accessKeyId: "{{ secret('HUAWEI_AK') }}"
                    secretAccessKey: "{{ secret('HUAWEI_SK') }}"
                    region: eu-west-101
                    topicUrn: "urn:smn:eu-west-101:0123456789abcdef0123456789abcdef:my-topic"
                    messageStructure:
                      default: "The nightly ETL job finished successfully."
                      email: "The nightly ETL job finished successfully. See the report attached."
                      sms: "ETL OK"
                """
        )
    },
    metrics = {
        @Metric(name = "smn.publish.messages", type = Counter.TYPE, unit = "messages",
            description = "Number of messages successfully published to SMN.")
    }
)
public class Publish extends AbstractSmn implements RunnableTask<Publish.Output> {

    @Schema(
        title = "Topic URN to publish to",
        description = "Format: `urn:smn:<region>:<project_id>:<topic_name>`, found on the SMN topic's detail page."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> topicUrn;

    @Schema(
        title = "Plain-text message body",
        description = "Mutually exclusive with `messageStructure` and `messageTemplateName` — exactly one must be set."
    )
    @PluginProperty(group = "main")
    private Property<String> message;

    @Schema(
        title = "Per-protocol message structure",
        description = """
            A map of subscription protocol (`email`, `sms`, `http`, `https`, `dms`, `functionstage`, ...) to
            the body delivered to subscribers of that protocol. Must include a `default` entry, used as the
            fallback body for any protocol not explicitly listed. Mutually exclusive with `message` and
            `messageTemplateName` — exactly one must be set.
            """
    )
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> messageStructure;

    @Schema(
        title = "SMN message template name",
        description = """
            Publishes via a pre-created SMN message template instead of an inline body. Combine with
            `tags` to fill the template's placeholders. Mutually exclusive with `message` and
            `messageStructure` — exactly one must be set.

            The template must include a variant with the `Default` protocol (SMN's mandatory fallback for
            every subscription protocol); otherwise SMN rejects the publish with `SMN.0076 Default message
            template not found`.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> messageTemplateName;

    @Schema(
        title = "Email subject",
        description = "Used only by subscriptions with the `email` protocol; ignored by other protocols."
    )
    @PluginProperty(group = "main")
    private Property<String> subject;

    @Schema(
        title = "Template placeholder values",
        description = "Key/value pairs filling the placeholders of `messageTemplateName`. Ignored unless `messageTemplateName` is set."
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, String>> tags;

    @Schema(title = "Structured message attributes")
    @PluginProperty(group = "advanced")
    private Property<List<MessageAttributeValue>> messageAttributes;

    @Schema(
        title = "Time-to-live",
        description = "How long SMN retains the message for retry before giving up, e.g. `3600` (seconds). Optional; SMN applies its own default when omitted."
    )
    @PluginProperty(group = "reliability")
    private Property<String> timeToLive;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rTopicUrn = runContext.render(topicUrn).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException(
                "'topicUrn' is required — set it to the target SMN topic's URN " +
                "(format: urn:smn:<region>:<project_id>:<topic_name>)."));

        var rMessage = runContext.render(message).as(String.class).orElse(null);
        var rMessageStructure = runContext.render(messageStructure).asMap(String.class, Object.class);
        var rMessageTemplateName = runContext.render(messageTemplateName).as(String.class).orElse(null);

        var hasMessage = rMessage != null && !rMessage.isBlank();
        var hasMessageStructure = !rMessageStructure.isEmpty();
        var hasMessageTemplateName = rMessageTemplateName != null && !rMessageTemplateName.isBlank();
        var modesSet = (hasMessage ? 1 : 0) + (hasMessageStructure ? 1 : 0) + (hasMessageTemplateName ? 1 : 0);
        if (modesSet != 1) {
            throw new IllegalArgumentException(
                "Exactly one of 'message', 'messageStructure', or 'messageTemplateName' must be set, but " +
                modesSet + " were provided — choose a single message mode for this publish call.");
        }

        var body = new PublishMessageRequestBody();

        if (hasMessage) {
            body.withMessage(rMessage);
        } else if (hasMessageStructure) {
            body.withMessageStructure(JacksonMapper.ofJson().writeValueAsString(rMessageStructure));
        } else {
            body.withMessageTemplateName(rMessageTemplateName);
        }

        var rSubject = runContext.render(subject).as(String.class).orElse(null);
        if (rSubject != null && !rSubject.isBlank()) {
            body.withSubject(rSubject);
        }

        var rTags = runContext.render(tags).asMap(String.class, String.class);
        if (!rTags.isEmpty()) {
            if (!hasMessageTemplateName) {
                throw new IllegalArgumentException(
                    "'tags' is only used together with 'messageTemplateName' — either set 'messageTemplateName' " +
                    "or remove 'tags'.");
            }
            body.withTags(rTags);
        }

        var rMessageAttributes = runContext.render(messageAttributes).asList(MessageAttributeValue.class);
        if (!rMessageAttributes.isEmpty()) {
            var sdkAttributes = new ArrayList<MessageAttribute>(rMessageAttributes.size());
            for (var attr : rMessageAttributes) {
                var rName = runContext.render(attr.getName()).as(String.class)
                    .orElseThrow(() -> new IllegalArgumentException("'messageAttributes[].name' is required for every entry."));
                var rType = runContext.render(attr.getType()).as(MessageAttributeType.class).orElse(MessageAttributeType.STRING);
                var rValue = runContext.render(attr.getValue()).as(String.class)
                    .orElseThrow(() -> new IllegalArgumentException("'messageAttributes[].value' is required for entry '" + rName + "'."));
                sdkAttributes.add(new MessageAttribute()
                    .withName(rName)
                    .withType(MessageAttribute.TypeEnum.fromValue(rType.name()))
                    .withValue(rValue));
            }
            body.withMessageAttributes(sdkAttributes);
        }

        var rTimeToLive = runContext.render(timeToLive).as(String.class).orElse(null);
        if (rTimeToLive != null && !rTimeToLive.isBlank()) {
            body.withTimeToLive(rTimeToLive);
        }

        var client = client(runContext);
        var request = new PublishMessageRequest().withTopicUrn(rTopicUrn).withBody(body);

        try {
            var response = client.publishMessage(request);
            runContext.metric(Counter.of("smn.publish.messages", 1));
            logger.info("Published message '{}' to SMN topic '{}'", response.getMessageId(), rTopicUrn);

            return Output.builder()
                .messageId(response.getMessageId())
                .requestId(response.getRequestId())
                .build();
        } catch (ServiceResponseException e) {
            throw new IllegalStateException(
                "SMN publish failed (HTTP " + e.getHttpStatusCode() + "): " + e.getErrorMsg() +
                remediationHint(e.getErrorCode(), e.getErrorMsg()), e);
        } catch (SdkException e) {
            throw new IllegalStateException("SMN SDK error publishing message: " + e.getMessage(), e);
        }
    }

    /**
     * Builds an actionable remediation hint appended to the wrapped SMN error.
     *
     * <p>Several SMN failures are console/account configuration issues the flow author must fix in the
     * SMN console rather than in the flow — most notably {@code SMN.0076}, raised when publishing via
     * {@code messageTemplateName} while the template has no {@code Default}-protocol variant (SMN uses
     * that variant as the fallback for every subscription protocol, so it is mandatory). A blanket
     * "check topicUrn/permissions" hint is misleading for those, so map the known codes to a concrete
     * next step and fall back to the generic hint otherwise. Matching is by error code <em>or</em>
     * error-message text, since the exact code population can vary by region/gateway.
     */
    static String remediationHint(String errorCode, String errorMsg) {
        var code = errorCode == null ? "" : errorCode.trim();
        var msg = errorMsg == null ? "" : errorMsg.toLowerCase(Locale.ROOT);

        if (code.equals("SMN.0076") || msg.contains("default message template")) {
            return " — the message template has no `Default`-protocol variant. In the SMN console " +
                "(Message Templates), create a template with the SAME name and Protocol `Default` " +
                "(the mandatory fallback for every protocol), then retry.";
        }
        if (code.equals("SMN.0027") || (msg.contains("template") && msg.contains("not found"))) {
            return " — no message template named in `messageTemplateName` exists in this region. " +
                "Create it in the SMN console (Message Templates), or correct `messageTemplateName`.";
        }
        if (code.equals("SMN.0021") || msg.contains("messagestructure")) {
            return " — `messageStructure` is invalid: it must be a JSON object that includes a `default` " +
                "entry alongside any per-protocol keys.";
        }
        return " — verify that 'topicUrn' is correct and that the AK/SK has 'SMN FullAccess' permission.";
    }

    public enum MessageAttributeType {
        STRING
    }

    @Value
    @Builder
    @Jacksonized
    public static class MessageAttributeValue {

        @Schema(title = "Attribute name")
        @NotNull
        @PluginProperty(group = "advanced")
        Property<String> name;

        @Schema(title = "Attribute type", description = "Currently only `STRING` is supported by SMN.")
        @Builder.Default
        @PluginProperty(group = "advanced")
        Property<MessageAttributeType> type = Property.ofValue(MessageAttributeType.STRING);

        @Schema(title = "Attribute value")
        @NotNull
        @PluginProperty(group = "advanced")
        Property<String> value;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "SMN-assigned message identifier")
        private final String messageId;

        @Schema(title = "SMN request identifier, useful for support tickets")
        private final String requestId;
    }
}
