package io.kestra.plugin.huawei.obs;

import com.obs.services.ObsClient;
import com.obs.services.ObsConfiguration;
import com.obs.services.model.GetObjectRequest;
import com.obs.services.model.ListObjectsRequest;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.huawei.AbstractConnection;
import io.kestra.plugin.huawei.obs.models.ObsObject;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
     * <p>Extracted as a static factory so that the {@link io.kestra.plugin.huawei.obs.tasks.Trigger},
     * which extends {@code AbstractTrigger} instead of {@code AbstractObs}, can share the same
     * client-creation logic without inheritance.
     */
    public static ObsClient buildClient(
        AbstractConnection.HuaweiClientConfig config,
        String rEndpointOverride,
        Boolean rPathStyle,
        AuthType rAuthType
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

        var endpoint = ObsUtils.obsEndpoint(rEndpointOverride, config.region());
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
     * Download result carrying everything the {@link io.kestra.plugin.huawei.obs.tasks.Download} task
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

        runContext.logger().debug("Downloading OBS object s3://{}/{}", bucket, key);

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
     * Lists OBS objects with optional prefix/delimiter/marker/maxKeys filtering, iterates all pages,
     * and applies an optional client-side regexp filter on the full key.
     *
     * @param obs       open {@link ObsClient} (caller owns lifecycle)
     * @param bucket    bucket to list
     * @param prefix    key prefix; may be {@code null}
     * @param delimiter grouping delimiter; may be {@code null}
     * @param marker    listing start marker (exclusive); may be {@code null}
     * @param maxKeys   page size sent to OBS (1–1000); {@code null} defaults to 1000
     * @param regexp    client-side key filter; {@code null} means no filter
     * @return all matching objects across all pages
     */
    public static List<ObsObject> list(
        ObsClient obs,
        String bucket,
        String prefix,
        String delimiter,
        String marker,
        Integer maxKeys,
        String regexp
    ) {
        var result = new ArrayList<ObsObject>();
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
                    result.add(ObsObject.from(sdkObj));
                }
            }

            if (listing.isTruncated()) {
                currentMarker = listing.getNextMarker();
            } else {
                break;
            }
        } while (true);

        return Collections.unmodifiableList(result);
    }

    /**
     * Extracts user-defined metadata from {@code getAllMetadata()}, stripping common prefix variants
     * so callers receive bare key/value pairs regardless of whether OBS or MinIO added a prefix.
     *
     * <p>OBS returns user metadata with an {@code x-obs-meta-} prefix; MinIO may double-prefix
     * to {@code x-amz-meta-x-obs-meta-}. We normalise to bare keys in all cases.
     */
    @SuppressWarnings("unchecked")
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
