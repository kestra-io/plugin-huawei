package io.kestra.plugin.huawei.obs;

import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;
import com.obs.services.exception.ObsException;
import com.obs.services.model.CopyObjectRequest;
import com.obs.services.model.DeleteObjectRequest;
import com.obs.services.model.GetObjectRequest;
import com.obs.services.model.ListObjectsRequest;
import com.obs.services.model.ObjectMetadata;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
import io.kestra.plugin.huawei.obs.models.ObsObject;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reusable OBS operations shared across multiple tasks and triggers.
 *
 * <p>All methods are static and RunContext-aware only where storage I/O is needed.
 * SDK calls are kept here so tasks stay thin. Signatures are designed so Copy, DeleteList,
 * Downloads, and Trigger can call them without modification.
 */
public final class ObsService {

    private ObsService() {
    }

    /**
     * Builds an {@link ObsClient} from rendered connection properties.
     *
     * <p>Extracted as a static factory so that the {@link io.kestra.plugin.huawei.obs.Trigger},
     * which extends {@code AbstractTrigger} instead of {@code AbstractObs}, can share the same
     * client-creation logic without inheritance.
     *
     * <p><strong>Ownership:</strong> the returned {@code ObsClient} holds an HTTP connection pool and
     * implements {@link AutoCloseable}. The caller owns its lifecycle and <em>must</em> close it —
     * always wrap the result in a try-with-resources. A fresh client is created per call (no reuse
     * across task/trigger runs), which keeps each run self-contained at the cost of pool setup;
     * that trade-off is intentional for Kestra's run-isolated execution model.
     */
    public static ObsClient buildClient(
        AbstractConnection.HuaweiClientConfig config,
        String rEndpointOverride,
        Boolean rPathStyle,
        AuthType rAuthType,
        String rEndpointSuffix
    ) {
        if (config.accessKeyId() == null || config.accessKeyId().isBlank()) {
            throw new IllegalArgumentException(
                "OBS requires `accessKeyId` (AK). OBS uses AK/SK request signing; IAM token auth " +
                "is not supported for object storage operations."
            );
        }
        if (config.secretAccessKey() == null || config.secretAccessKey().isBlank()) {
            throw new IllegalArgumentException(
                "OBS requires `secretAccessKey` (SK). OBS uses AK/SK request signing; IAM token auth " +
                "is not supported for object storage operations."
            );
        }

        var endpoint = ObsUtils.obsEndpoint(rEndpointOverride, config.region(), rEndpointSuffix);
        var obsConfig = new ObsConfiguration();
        obsConfig.setEndPoint(endpoint);
        obsConfig.setPathStyle(rPathStyle != null && rPathStyle);
        obsConfig.setAuthType((rAuthType != null ? rAuthType : AuthType.OBS).toSdkEnum());
        obsConfig.setHttpsOnly(endpoint.startsWith("https://"));

        if (config.securityToken() != null && !config.securityToken().isBlank()) {
            return new ObsClient(config.accessKeyId(), config.secretAccessKey(), config.securityToken(), obsConfig);
        }
        return new ObsClient(config.accessKeyId(), config.secretAccessKey(), obsConfig);
    }

    /**
     * Download result carrying everything the {@link io.kestra.plugin.huawei.obs.Download} task
     * needs to populate its output.
     */
    public record DownloadResult(
        URI uri,
        Long contentLength,
        String contentType,
        Map<String, String> metadata
    ) {
    }

    /**
     * Downloads an OBS object body into Kestra internal storage and returns a {@link DownloadResult}.
     *
     * <p>The object is buffered through a local temp file so the HTTP connection is released promptly
     * before the storage upload begins.
     *
     * @param obs       open {@link ObsClient} (caller owns lifecycle)
     * @param runContext current run context used for storage and logging
     * @param bucket    bucket containing the object
     * @param key       object key
     * @param versionId optional version ID; pass {@code null} to get the latest version
     */
    public static DownloadResult download(
        ObsClient obs,
        RunContext runContext,
        String bucket,
        String key,
        String versionId
    ) throws Exception {
        var req = new GetObjectRequest(bucket, key);
        if (versionId != null && !versionId.isBlank()) {
            req.setVersionId(versionId);
        }

        runContext.logger().debug("Downloading OBS object obs://{}/{}", bucket, key);

        var sdkObj = obs.getObject(req);
        var sdkMeta = sdkObj.getMetadata();

        // Buffer via temp file so the HTTP connection is closed before we push to storage
        var tmp = runContext.workingDir().createTempFile();
        try (InputStream body = sdkObj.getObjectContent()) {
            Files.copy(body, tmp, StandardCopyOption.REPLACE_EXISTING);
        }

        URI storageUri;
        try (var in = Files.newInputStream(tmp)) {
            storageUri = runContext.storage().putFile(in, tmp.getFileName().toString());
        }

        Map<String, String> userMetadata = extractUserMetadata(sdkMeta);

        return new DownloadResult(
            storageUri,
            sdkMeta.getContentLength(),
            sdkMeta.getContentType(),
            userMetadata
        );
    }

    /**
     * Server-side moves an object: copies it to the destination, verifies the copy fully landed, and
     * only then deletes the source. If verification fails the source is left intact and an exception is
     * thrown, so a silently-incomplete copy (partial write, network glitch) can never lose data.
     *
     * @param obs        open {@link ObsClient} (caller owns lifecycle)
     * @param srcBucket  source bucket
     * @param srcKey     source key
     * @param destBucket destination bucket
     * @param destKey    destination key
     * @param sourceEtag source ETag (e.g. from the listing); may be {@code null}
     * @param sourceSize source size in bytes (e.g. from the listing); may be {@code null}
     */
    public static void move(
        ObsClient obs,
        String srcBucket,
        String srcKey,
        String destBucket,
        String destKey,
        String sourceEtag,
        Long sourceSize
    ) {
        var copyResult = obs.copyObject(new CopyObjectRequest(srcBucket, srcKey, destBucket, destKey));
        verifyServerSideCopy(obs, destBucket, destKey, sourceEtag, sourceSize, copyResult.getEtag());
        obs.deleteObject(new DeleteObjectRequest(srcBucket, srcKey));
    }

    /**
     * Confirms a server-side copy fully landed at the destination, throwing {@link IllegalStateException}
     * (so the caller can safely abort before deleting any source) when it cannot be confirmed.
     *
     * <p>Verification first compares the copy's ETag against {@code sourceEtag}: simple (non-multipart)
     * ETags are content MD5s and must match exactly. Multipart ETags (suffixed {@code -<n>}) legitimately
     * change across a server-side copy, so when either side is multipart — or an ETag is unavailable — it
     * falls back to confirming the destination exists with a content length matching {@code sourceSize}.
     *
     * @param copyEtag the ETag returned by the copy operation (destination ETag); may be {@code null}
     */
    public static void verifyServerSideCopy(
        ObsClient obs,
        String destBucket,
        String destKey,
        String sourceEtag,
        Long sourceSize,
        String copyEtag
    ) {
        var src = normalizeEtag(sourceEtag);
        var dst = normalizeEtag(copyEtag);

        // Simple (non-multipart) ETags are content MD5s and must match exactly.
        if (src != null && dst != null && !src.contains("-") && !dst.contains("-")) {
            if (src.equals(dst)) {
                return;
            }
            throw new IllegalStateException(
                "Copy verification failed: ETag mismatch at destination obs://" + destBucket + "/" + destKey +
                " (source=" + src + ", copy=" + dst + "); source left intact to avoid data loss"
            );
        }

        // Multipart or unavailable ETag: confirm the destination exists with the expected size instead.
        ObjectMetadata meta;
        try {
            meta = obs.getObjectMetadata(destBucket, destKey);
        } catch (ObsException e) {
            throw new IllegalStateException(
                "Copy verification failed: destination obs://" + destBucket + "/" + destKey +
                " could not be read after copy; source left intact to avoid data loss", e
            );
        }
        if (meta == null) {
            throw new IllegalStateException(
                "Copy verification failed: destination obs://" + destBucket + "/" + destKey +
                " not found after copy; source left intact to avoid data loss"
            );
        }
        if (sourceSize != null && meta.getContentLength() != null
            && !sourceSize.equals(meta.getContentLength())) {
            throw new IllegalStateException(
                "Copy verification failed: size mismatch at destination obs://" + destBucket + "/" + destKey +
                " (source=" + sourceSize + ", copy=" + meta.getContentLength() +
                "); source left intact to avoid data loss"
            );
        }
    }

    /** Strips surrounding quotes and whitespace from an ETag; returns {@code null} for blank/absent values. */
    private static String normalizeEtag(String etag) {
        if (etag == null) {
            return null;
        }
        var trimmed = etag.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Callback invoked once per matching object during a streaming {@link #list}.
     *
     * <p>Allowed to throw checked exceptions so callers can perform I/O (download, delete) inline
     * without wrapping.
     */
    @FunctionalInterface
    public interface ObsObjectConsumer {
        void accept(ObsObject object) throws Exception;
    }

    /**
     * Streams OBS objects to {@code consumer} one at a time, paginating through all pages and applying
     * an optional client-side regexp filter on the full key. Nothing is accumulated in memory: this is
     * the memory-safe primitive for callers that process-and-discard (delete, download) on buckets that
     * may hold millions of objects.
     *
     * @param obs       open {@link ObsClient} (caller owns lifecycle)
     * @param bucket    bucket to list
     * @param prefix    key prefix; may be {@code null}
     * @param delimiter grouping delimiter; may be {@code null}
     * @param marker    listing start marker (exclusive); may be {@code null}
     * @param maxKeys   page size sent to OBS (1–1000); {@code null} defaults to 1000
     * @param regexp    client-side key filter; {@code null} means no filter
     * @param consumer  invoked for each matching object, in listing order
     */
    public static void list(
        ObsClient obs,
        String bucket,
        String prefix,
        String delimiter,
        String marker,
        Integer maxKeys,
        String regexp,
        ObsObjectConsumer consumer
    ) throws Exception {
        var currentMarker = marker;
        var pattern = regexp != null ? Pattern.compile(regexp) : null;

        do {
            var req = new ListObjectsRequest(bucket);
            if (prefix != null) req.setPrefix(prefix);
            if (delimiter != null) req.setDelimiter(delimiter);
            if (currentMarker != null) req.setMarker(currentMarker);
            req.setMaxKeys(maxKeys != null ? maxKeys : 1000);

            var listing = obs.listObjects(req);
            for (var sdkObj : listing.getObjects()) {
                if (pattern == null || pattern.matcher(sdkObj.getObjectKey()).matches()) {
                    consumer.accept(ObsObject.from(sdkObj));
                }
            }

            if (listing.isTruncated()) {
                currentMarker = listing.getNextMarker();
            } else {
                break;
            }
        } while (true);
    }

    /**
     * Extracts user-defined metadata from {@code getAllMetadata()}, stripping common prefix variants
     * so callers receive bare key/value pairs regardless of whether OBS or MinIO added a prefix.
     *
     * <p>OBS returns user metadata with an {@code x-obs-meta-} prefix; MinIO may double-prefix
     * to {@code x-amz-meta-x-obs-meta-}. We normalise to bare keys in all cases.
     */
    static Map<String, String> extractUserMetadata(com.obs.services.model.ObjectMetadata meta) {
        if (meta == null) {
            return Map.of();
        }
        var allMeta = meta.getAllMetadata();
        if (allMeta == null || allMeta.isEmpty()) {
            return Map.of();
        }
        return allMeta.entrySet().stream()
            .filter(e -> e.getValue() != null)
            .filter(e -> isUserMetaKey(e.getKey()))
            .collect(Collectors.toUnmodifiableMap(
                e -> stripMetaPrefix(e.getKey()),
                e -> String.valueOf(e.getValue()),
                (a, b) -> a
            ));
    }

    private static boolean isUserMetaKey(String key) {
        var lc = key.toLowerCase();
        return lc.startsWith("x-obs-meta-") || lc.startsWith("x-amz-meta-");
    }

    private static String stripMetaPrefix(String key) {
        var lc = key.toLowerCase();
        if (lc.startsWith("x-amz-meta-x-obs-meta-")) {
            return key.substring("x-amz-meta-x-obs-meta-".length());
        }
        if (lc.startsWith("x-obs-meta-")) {
            return key.substring("x-obs-meta-".length());
        }
        if (lc.startsWith("x-amz-meta-")) {
            return key.substring("x-amz-meta-".length());
        }
        return key;
    }
}
