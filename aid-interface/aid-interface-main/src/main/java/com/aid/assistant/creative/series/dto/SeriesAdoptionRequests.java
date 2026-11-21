package com.aid.assistant.creative.series.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Explicit project-scope series adoption commands. Mode-specific rules are checked by the service. */
public final class SeriesAdoptionRequests {
    private static final String ID = "^[1-9][0-9]{0,18}$";
    private static final String SHA256 = "^[0-9a-f]{64}$";

    private SeriesAdoptionRequests() {
    }

    @Data
    public static class Capabilities {
        @NotBlank @Pattern(regexp = ID)
        private String sessionId;
        @NotBlank @Pattern(regexp = "UPDATE_EXISTING|APPEND_NEW")
        private String mode;
        @Valid @Size(max = 100)
        private List<CapabilityItem> items;
        @Pattern(regexp = ID)
        private String artifactId;
        @Size(max = 100)
        private String episodeKeyword;
    }

    @Data
    public static class CapabilityItem {
        @NotBlank @Pattern(regexp = ID)
        private String artifactId;
        @NotBlank @Pattern(regexp = ID)
        private String episodeId;
    }

    @Data
    public static class Commit {
        @NotBlank @Pattern(regexp = ID)
        private String sessionId;
        @NotBlank @Pattern(regexp = "UPDATE_EXISTING|APPEND_NEW")
        private String mode;
        @NotBlank @Pattern(regexp = SHA256)
        private String capabilityDigest;
        @NotBlank @Pattern(regexp = SHA256)
        private String expectedProjectEpisodeSetDigest;
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}")
        private String clientRequestId;
        @Valid @Size(max = 100)
        private List<CommitItem> items;
        @Pattern(regexp = ID)
        private String artifactId;
        @Pattern(regexp = SHA256)
        private String artifactContentHash;
        /** Canonical base64url capability snapshot. It is candidate input and never grants authority. */
        @Size(max = 180000)
        private String splitCapabilitySnapshot;
    }

    @Data
    public static class CommitItem {
        @NotBlank @Pattern(regexp = ID)
        private String artifactId;
        @NotBlank @Pattern(regexp = SHA256)
        private String artifactContentHash;
        @NotBlank @Pattern(regexp = ID)
        private String episodeId;
        @NotBlank @Size(max = 128)
        private String expectedTargetRevision;
    }
}
