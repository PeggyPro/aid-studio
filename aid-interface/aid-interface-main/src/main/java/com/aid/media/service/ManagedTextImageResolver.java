package com.aid.media.service;

/** Module-owned immutable image lookup; implementations must authorize the actor on every read. */
public interface ManagedTextImageResolver {
    boolean supports(String reference);
    Image resolve(Long actor, String reference);
    record Image(byte[] bytes, String mimeType, int width, int height) { }
}
