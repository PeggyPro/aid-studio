package com.aid.common.aid.image;

import com.aid.common.aid.oss.core.ManagedStorageResourceReader;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.utils.image.ImageUrlValidator;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Validate authorized site images through storage; external whitelist URLs retain public URL validation. */
@Component
@RequiredArgsConstructor
public class ManagedImageResourceValidator {
    private final ManagedStorageResourceReader reader;
    private final MediaUrlResolver resolver;

    /** Business ownership and external whitelist checks belong to the caller. */
    public boolean isReadableImage(String locator) {
        if (!resolver.isSiteMediaUrl(locator)) return ImageUrlValidator.isValidRemoteImageUrl(locator);
        var content = reader.read(locator, 20 * 1024 * 1024, 10_000);
        if (content.failureKind() != null || content.truncated() || content.bytes().length == 0) return false;
        try (var stream = ManagedImageIO.createImageInputStream(new ByteArrayInputStream(content.bytes()))) {
            if (stream == null) return false;
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return false;
            var image = readers.next();
            try {
                image.setInput(stream, true, true);
                return image.getWidth(0) > 0 && image.getHeight(0) > 0;
            } finally { image.dispose(); }
        } catch (java.io.IOException failure) { return false; }
    }
}
