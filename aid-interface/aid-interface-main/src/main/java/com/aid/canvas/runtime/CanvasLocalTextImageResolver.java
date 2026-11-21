package com.aid.canvas.runtime;

import com.aid.canvas.domain.CanvasEntities.CanvasResource;
import com.aid.canvas.service.CanvasResourceService;
import com.aid.common.exception.ServiceException;
import com.aid.media.service.ManagedTextImageResolver;
import com.aid.media.service.VerifiedMediaMetadataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Delivers authorized Canvas images that generation stored locally after remote storage failed. */
@Component
@RequiredArgsConstructor
public class CanvasLocalTextImageResolver implements ManagedTextImageResolver {
    private static final String PREFIX = "aid-image://canvas-local/";
    private static final Pattern REFERENCE = Pattern.compile(PREFIX + "([1-9][0-9]{0,18})-([1-9][0-9]{0,18})/([a-f0-9]{64})");
    private static final Set<String> FORMATS = Set.of("image/png", "image/jpeg");
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private final CanvasResourceService resources;
    private final VerifiedMediaMetadataService metadata;

    public String referenceIfAvailable(CanvasResource resource) {
        if (!eligible(resource) || !metadata.hasGeneratedStorageFallback(resource.getStorageUrl())) return null;
        return PREFIX + resource.getWorkspaceId() + "-" + resource.getId()
                + "/" + CanvasResourceRuntimeScopeAccess.identityHash(resource);
    }

    @Override
    public boolean supports(String reference) {
        return reference != null && REFERENCE.matcher(reference).matches();
    }

    @Override
    public Image resolve(Long actor, String reference) {
        if (actor == null || reference == null) throw unavailable();
        var match = REFERENCE.matcher(reference);
        if (!match.matches()) throw unavailable();
        CanvasResource resource;
        try {
            resource = resources.requireResources(Long.valueOf(match.group(1)),
                    List.of(Long.valueOf(match.group(2))), actor).get(0);
        } catch (RuntimeException rejected) {
            throw unavailable();
        }
        if (!eligible(resource) || !match.group(3).equals(CanvasResourceRuntimeScopeAccess.identityHash(resource))
                || !metadata.hasGeneratedStorageFallback(resource.getStorageUrl())) throw unavailable();
        byte[] bytes = metadata.readTrustedBytes(resource.getStorageUrl(), MAX_BYTES);
        var facts = metadata.inspect(resource.getStorageUrl(), "image");
        String mime = "png".equals(facts.format()) ? "image/png"
                : ("jpeg".equals(facts.format()) || "jpg".equals(facts.format())) ? "image/jpeg" : null;
        if (mime == null || facts.sizeBytes() != bytes.length) throw unavailable();
        return new Image(bytes, mime, facts.width(), facts.height());
    }

    private boolean eligible(CanvasResource resource) {
        return resource != null && "ACTIVE".equals(resource.getStatus()) && "IMAGE".equals(resource.getMediaType())
                && resource.getMimeType() != null && FORMATS.contains(resource.getMimeType())
                && resource.getWorkspaceId() != null && resource.getId() != null;
    }

    private ServiceException unavailable() {
        return new ServiceException("画布内部图片已变更或不可读取");
    }
}
