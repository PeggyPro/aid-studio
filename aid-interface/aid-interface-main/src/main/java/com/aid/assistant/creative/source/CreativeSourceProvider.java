package com.aid.assistant.creative.source;

import java.io.IOException;
import java.io.Reader;

/** Server-side adapter that re-authorizes and opens one immutable source revision. */
public interface CreativeSourceProvider {
    String sourceKind();

    AuthorizedSource authorize(Long userId, String sourceObjectId, String requestedRevision);

    @FunctionalInterface
    interface ReaderFactory {
        Reader open() throws IOException;
    }

    record AuthorizedSource(String sourceObjectId, String sourceRevision,
                            String accessScopeDigest, String locatorJson,
                            ReaderFactory readerFactory) {
    }
}
