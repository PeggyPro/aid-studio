package com.aid.director;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 虚拟相机受控运行参数。 */
@Data
@Component
@ConfigurationProperties(prefix = "director.vcam")
public class DirectorProperties {
    private boolean enabled;
    private String allowedOrigins = "";
    private String mobileEntryUrl = "";
    private String signalingPath = "/api/user/studio/director/virtual-camera/ws";
    private String storageRoot = System.getProperty("user.home") + "/.aid/director-objects";
    private int maxRoomsPerUser = 2;
    private int maxRooms = 200;
    private int sessionSeconds = 1800;
    private int leaseSeconds = 5;
    private int draftDays = 7;
    private int maxScenesPerScope = 64;
    private int maxDraftsPerUser = 200;
    private int maxSceneWrites = 4;
    private int maxChunkRequests = 32;
    private int minFreeStorageMb = 512;

    @jakarta.annotation.PostConstruct
    public void validate() {
        if (!enabled) return;
        if (maxRooms < 1 || maxRooms > 2000 || maxRoomsPerUser < 1 || maxRoomsPerUser > 20
                || sessionSeconds < 120 || sessionSeconds > 1800 || leaseSeconds < 1 || leaseSeconds > 5
                || draftDays < 1 || draftDays > 30 || maxScenesPerScope < 1 || maxScenesPerScope > 256
                || maxDraftsPerUser < 1 || maxDraftsPerUser > 1000 || maxSceneWrites < 1 || maxSceneWrites > 16
                || maxChunkRequests < 1 || maxChunkRequests > 64 || minFreeStorageMb < 64 || minFreeStorageMb > 1048576
                || storageRoot == null || storageRoot.isBlank()
                || !"/api/user/studio/director/virtual-camera/ws".equals(signalingPath))
            throw new IllegalStateException("invalid director limits");
        if (allowedOrigins == null || allowedOrigins.isBlank()) throw new IllegalStateException("director origins required");
        for (String origin : allowedOrigins.split(",")) {
            java.net.URI uri = java.net.URI.create(origin.trim());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty())
                throw new IllegalStateException("director origins must be exact HTTPS origins");
        }
        if (mobileEntryUrl != null && !mobileEntryUrl.isBlank()) {
            java.net.URI uri = java.net.URI.create(mobileEntryUrl);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalStateException("director mobile entry must be HTTPS");
        }
    }

    public void requireEnabled() {
        if (!enabled) throw new DirectorException("FEATURE_DISABLED", 503, "功能暂未启用");
    }
}
