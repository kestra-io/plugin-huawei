package io.kestra.plugin.huawei.dis;

import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.dis.v2.DisClient;
import com.huaweicloud.sdk.dis.v2.model.ShowCursorRequest;
import com.huaweicloud.sdk.dis.v2.model.ShowStreamRequest;
import com.huaweicloud.sdk.dis.v2.region.DisRegion;
import io.kestra.plugin.huawei.AbstractConnection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Static helpers shared by {@link AbstractDis} (tasks) and {@link AbstractDisTrigger} (triggers) —
 * client construction, partition enumeration, and cursor resolution, kept out of both base classes
 * so the logic lives in exactly one place, mirroring {@code io.kestra.plugin.huawei.obs.ObsService}.
 */
final class DisService {

    private DisService() {
    }

    static DisClient buildClient(
        AbstractConnection.HuaweiClientConfig config,
        String rOverride,
        String rRegion,
        String rSuffix
    ) {
        DisUtils.requireProjectIdForCustomEndpoint(rOverride, rSuffix, config.projectId());

        var creds = buildCredentials(config);
        var builder = DisClient.newBuilder().withCredential(creds);

        if (rOverride != null && !rOverride.isBlank()) {
            builder.withEndpoint(DisUtils.stripTrailingSlash(rOverride.trim()));
        } else if (rRegion != null && !rRegion.isBlank()) {
            // An explicit endpointSuffix forces suffix-derived resolution even for regions present in the SDK
            // enum: the enum hard-codes the `.myhuaweicloud.com` domain, which is wrong for sovereign clouds
            // (e.g. `myhuaweicloud.eu`). Without a suffix, prefer the SDK enum and fall back to derivation only
            // for regions not yet in it (e.g. newly added regions).
            if (rSuffix != null && !rSuffix.isBlank()) {
                builder.withEndpoint(DisUtils.disEndpoint(null, rRegion, rSuffix));
            } else {
                try {
                    builder.withRegion(DisRegion.valueOf(rRegion));
                } catch (IllegalArgumentException e) {
                    builder.withEndpoint(DisUtils.disEndpoint(null, rRegion, null));
                }
            }
        } else {
            throw new IllegalArgumentException(
                "DIS requires either `endpointOverride` or `region` to be set — " +
                "set the 'region' property (e.g. eu-west-101) or provide an explicit 'endpointOverride'.");
        }

        return builder.build();
    }

    private static BasicCredentials buildCredentials(AbstractConnection.HuaweiClientConfig config) {
        if (config.accessKeyId() == null || config.accessKeyId().isBlank()) {
            throw new IllegalArgumentException(
                "AK/SK credentials are required — set 'accessKeyId' and 'secretAccessKey' properties, " +
                "or configure 'temporaryCredentials' for inline IAM credential exchange.");
        }
        if (config.secretAccessKey() == null || config.secretAccessKey().isBlank()) {
            throw new IllegalArgumentException(
                "AK/SK credentials are incomplete — 'secretAccessKey' is required when 'accessKeyId' is set.");
        }
        var creds = new BasicCredentials()
            .withAk(config.accessKeyId())
            .withSk(config.secretAccessKey());
        if (config.securityToken() != null) {
            creds.withSecurityToken(config.securityToken());
        }
        if (config.projectId() != null) {
            creds.withProjectId(config.projectId());
        }
        return creds;
    }

    /** Enumerates every partition id of a stream, following DIS's {@code hasMorePartitions} pagination. */
    static List<String> listPartitionIds(DisClient client, String streamName) {
        var partitionIds = new ArrayList<String>();
        String startPartitionId = null;
        boolean hasMore;
        do {
            var request = new ShowStreamRequest().withStreamName(streamName);
            if (startPartitionId != null) {
                request.withStartPartitionId(startPartitionId);
            }
            var response = client.showStream(request);
            var partitions = response.getPartitions();
            if (partitions != null && !partitions.isEmpty()) {
                for (var partition : partitions) {
                    partitionIds.add(partition.getPartitionId());
                }
                startPartitionId = partitions.getLast().getPartitionId();
            }
            hasMore = Boolean.TRUE.equals(response.getHasMorePartitions()) && partitions != null && !partitions.isEmpty();
        } while (hasMore);

        if (partitionIds.isEmpty()) {
            throw new IllegalStateException(
                "DIS stream '" + streamName + "' has no partitions — verify the stream exists and is active.");
        }
        return partitionIds;
    }

    /**
     * Resolves a partition cursor. When {@code resumeAfterSequenceNumber} is set (a previously
     * delivered sequence number, e.g. from a persisted watermark), the cursor resumes right after it
     * regardless of {@code startingPosition} — this is how {@code Trigger} and {@code RealtimeTrigger}
     * avoid re-delivering records already seen. Otherwise, the cursor is derived from
     * {@code startingPosition} (and {@code startingTimestamp} for {@code AT_TIMESTAMP}).
     */
    static String cursorFor(
        DisClient client,
        String streamName,
        String partitionId,
        StartingPosition startingPosition,
        Instant startingTimestamp,
        String resumeAfterSequenceNumber
    ) {
        var request = new ShowCursorRequest().withStreamName(streamName).withPartitionId(partitionId);

        if (resumeAfterSequenceNumber != null) {
            request.withCursorType(ShowCursorRequest.CursorTypeEnum.AFTER_SEQUENCE_NUMBER)
                .withStartingSequenceNumber(resumeAfterSequenceNumber);
        } else {
            switch (startingPosition) {
                case LATEST -> request.withCursorType(ShowCursorRequest.CursorTypeEnum.LATEST);
                case AT_TIMESTAMP -> {
                    if (startingTimestamp == null) {
                        throw new IllegalArgumentException(
                            "'startingTimestamp' is required when 'startingPosition' is AT_TIMESTAMP");
                    }
                    request.withCursorType(ShowCursorRequest.CursorTypeEnum.AT_TIMESTAMP)
                        .withTimestamp(startingTimestamp.toEpochMilli());
                }
                default -> request.withCursorType(ShowCursorRequest.CursorTypeEnum.TRIM_HORIZON);
            }
        }

        return client.showCursor(request).getPartitionCursor();
    }
}
