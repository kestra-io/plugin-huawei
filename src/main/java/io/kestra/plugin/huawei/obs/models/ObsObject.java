package io.kestra.plugin.huawei.obs.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.net.URI;
import java.time.Instant;

/**
 * Lightweight, serializable representation of an OBS object entry returned by listing operations.
 *
 * <p>Named {@code ObsObject} to avoid shadowing {@code com.obs.services.model.ObsObject} from the SDK.
 * Use a static import alias or a fully-qualified name at the call sites that handle both.
 */
@Value
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ObsObject {

    @Schema(title = "Object key (full path within the bucket).")
    String key;

    @Schema(title = "ETag of the object, as returned by OBS.")
    String etag;

    @Schema(title = "Object size in bytes.")
    Long size;

    @Schema(title = "Last-modified timestamp (UTC).")
    Instant lastModified;

    @Schema(title = "Display name of the object owner, or null when not available.")
    String owner;

    @Schema(
        title = "Kestra internal storage URI of the downloaded object.",
        description = "Populated only by tasks that download the object content (e.g. `Downloads`, `Trigger`). " +
            "Null when produced by listing-only operations."
    )
    URI uri;

    /**
     * Maps an SDK {@code ObsObject} (from a listing) to this model.
     *
     * <p>The SDK reuses {@code ObsObject} for both list entries and get-object responses. In a listing
     * context the object has no body ({@code getObjectContent()} is null) and metadata is stored in
     * {@code getMetadata()}.
     */
    public static ObsObject from(com.obs.services.model.ObsObject sdkObj) {
        var meta = sdkObj.getMetadata();
        String owner = sdkObj.getOwner() != null ? sdkObj.getOwner().getDisplayName() : null;
        Instant lastModified = meta != null && meta.getLastModified() != null
            ? meta.getLastModified().toInstant()
            : null;
        Long size = meta != null ? meta.getContentLength() : null;
        String etag = meta != null ? meta.getEtag() : null;
        return ObsObject.builder()
            .key(sdkObj.getObjectKey())
            .etag(etag)
            .size(size)
            .lastModified(lastModified)
            .owner(owner)
            .build();
    }
}
