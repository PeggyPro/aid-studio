package com.aid.previz.workspace;

/** Admission limits for new writes. Existing immutable versions remain readable after a reduction. */
@FunctionalInterface
public interface PrevizStoragePolicy {
    PrevizStoragePolicy DEFAULT = (key, fallback) -> fallback;
    long limit(Key key, long fallback);

    enum Key {
        FILE_COUNT("files.maximum-files", 1, 5000),
        FILE_BYTES("files.active-quota-bytes", 10485760, 4L << 40),
        STORED_BYTES("files.stored-quota-bytes", 10485760, 4L << 40),
        STORED_OBJECTS("files.maximum-stored-objects", 1, 1000000),
        DOCUMENT_BYTES("documents.project-quota-bytes", 16777216, 4L << 40),
        DOCUMENT_VERSIONS("documents.maximum-versions", 1, 1000000),
        SOURCE_BYTES("sources.project-quota-bytes", 8388608, 4L << 40),
        SOURCE_VERSIONS("sources.maximum-versions", 1, 1000000),
        ASSET_BYTES("assets.project-quota-bytes", 33554432, 4L << 40),
        FREE_BYTES("assets.minimum-free-bytes", 0, 4L << 40),
        PENDING_UPLOADS("assets.maximum-pending-uploads", 1, 1024),
        ASSET_COUNT("assets.maximum-project-assets", 1, 1000000),
        UPLOAD_RECORDS("assets.maximum-upload-records", 1, 4000000);

        public final String suffix;
        private final long minimum, maximum;
        Key(String suffix, long minimum, long maximum) { this.suffix=suffix;this.minimum=minimum;this.maximum=maximum; }
        public String configKey() { return "aid.previz."+suffix; }
        public long minimum() { return minimum; }
        public long maximum() { return maximum; }
        public long parse(String value, long fallback) {
            if(value==null||value.isBlank())return fallback;
            try { long parsed=Long.parseLong(value);if(parsed>=minimum&&parsed<=maximum)return parsed; }
            catch(NumberFormatException ignored) { }
            throw new IllegalArgumentException("工作区存储参数无效："+configKey());
        }
    }
}
