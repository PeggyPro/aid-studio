package com.aid.assistant.source.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/** Wire requests for user-owned, recoverable document imports. */
public final class SourceImportRequests {
    private SourceImportRequests() {
    }

    public static final String ID = "^[1-9][0-9]{0,18}$";
    public static final String KEY = "^[A-Za-z0-9._:-]{1,64}$";

    @Data
    public static class Upload {
        @NotBlank @Pattern(regexp = ID)
        private String sessionId;
        @NotBlank @Pattern(regexp = KEY)
        private String clientRequestId;
        @NotNull
        private MultipartFile file;
    }

    @Data
    public static class Page {
        @NotBlank @Pattern(regexp = ID)
        private String sessionId;
        @Pattern(regexp = ID)
        private String afterDocumentId;
        @Min(1) @Max(50)
        private Integer limit = 20;
    }

    @Data
    public static class Lookup {
        @NotBlank @Pattern(regexp = ID)
        private String sessionId;
        @NotBlank @Pattern(regexp = KEY)
        private String clientRequestId;
    }

    @Data
    public static class Content {
        @NotBlank @Pattern(regexp = ID) private String sessionId;
        @NotBlank @Pattern(regexp = ID) private String documentId;
        @Min(-1) private Integer afterChunkIndex = -1;
        @Min(1) @Max(20) private Integer limit = 20;
    }

    @Data
    public static class Detail {
        @NotBlank @Pattern(regexp = ID)
        private String sessionId;
        @NotBlank @Pattern(regexp = ID)
        private String documentId;
        @Min(-1)
        private Integer afterPartIndex = -1;
        @Min(1) @Max(100)
        private Integer partLimit = 100;
    }

    @Data
    public static class Advance {
        @NotBlank @Pattern(regexp = ID)
        private String sessionId;
        @NotBlank @Pattern(regexp = ID)
        private String documentId;
        @NotNull @Min(0)
        private Long expectedStateRevision;
        @NotBlank @Pattern(regexp = KEY)
        private String clientRequestId;
        @Size(max = 20)
        private List<@NotNull @Min(0) Integer> partIndexes = new ArrayList<>();
        @Min(1) @Max(20)
        private Integer maxParts = 20;
    }

    @Data
    public static class Cancel {
        @NotBlank @Pattern(regexp = ID)
        private String sessionId;
        @NotBlank @Pattern(regexp = ID)
        private String documentId;
        @NotNull @Min(0)
        private Long expectedStateRevision;
        @NotBlank @Pattern(regexp = KEY)
        private String clientRequestId;
    }
}
