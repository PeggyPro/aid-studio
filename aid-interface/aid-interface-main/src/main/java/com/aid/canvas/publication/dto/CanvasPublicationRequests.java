package com.aid.canvas.publication.dto;

import com.alibaba.fastjson2.JSONObject;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

public final class CanvasPublicationRequests {
    private CanvasPublicationRequests() {}
    @Data public static class Project {
        @NotNull @Positive private Long projectId;
    }
    @Data @EqualsAndHashCode(callSuper = true) public static class Submit extends Project {
        @NotBlank @Size(max=64) private String operationId;
        @NotNull @PositiveOrZero private Long expectedRevision;
        @NotNull @PositiveOrZero private Long expectedSceneVersion;
        @Size(max=100) private String projectName;
        @NotBlank @Size(max=500) private String projectDesc;
        @NotNull @Positive private Long videoResourceId;
        @NotNull @Positive private Long coverResourceId;
        @NotNull private Boolean publicCanvas;
        private boolean allowCopy;
    }
    @Data @EqualsAndHashCode(callSuper = true) public static class Change extends Project {
        @NotNull @PositiveOrZero private Long expectedVersion;
        @NotBlank @Pattern(regexp="WITHDRAW|DISABLE_COPY|CANCEL_REVIEW") private String action;
    }
    @Data @EqualsAndHashCode(callSuper = true) public static class Scene extends Project {
        @NotNull @PositiveOrZero private Long expectedVersion;
        @NotNull private JSONObject document;
    }
    @Data public static class Copy {
        @NotNull @Positive private Long publicationId;
        @NotBlank @Size(max=64) private String operationId;
        @Size(max=100) private String projectName;
    }
    @Data public static class Review {
        @NotNull @Positive private Long publicationId;
        @NotNull @PositiveOrZero private Long expectedVersion;
        @NotBlank @Pattern(regexp="APPROVE|REJECT|OFFLINE") private String action;
        @Size(max=1000) private String reason;
    }
    @Data public static class Detail {
        @NotNull @Positive private Long publicationId;
    }
    @Data public static class Operation {
        @NotBlank @Size(max=64) private String operationId;
    }
    @Data public static class Page {
        @Min(1) @Max(1000000) private int pageNum=1;
        @Min(1) @Max(100) private int pageSize=20;
        @Size(max=100) private String keyword;
        @Pattern(regexp="PENDING|APPROVED|REJECTED|CANCELLED") private String reviewStatus;
    }
}
