package com.aid.assistant.source.service;

/** Private durable blob boundary. Locators are server-owned and never exposed to clients or Agents. */
public interface AssistantSourceBlobStore {
    String store(byte[] immutableBytes, String safeExtension);

    byte[] read(String serverLocator, long expectedByteCount);

    void deleteQuietly(String serverLocator);
}
