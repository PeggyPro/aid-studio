package com.aid.canvas.model;

import jakarta.validation.constraints.*;
import lombok.Data;

public final class CanvasModelLibraryRequests {
    private CanvasModelLibraryRequests() { }
    @Data public static class Key { @NotBlank @Pattern(regexp="[a-f0-9]{32}") private String id; }
    @Data public static class Query { @Min(1) private int pageNum=1; @Min(1) @Max(50) private int pageSize=20; @Size(max=100) private String keyword; @Size(max=32) private String categoryId; }
    @Data public static class Category { @Pattern(regexp="[a-f0-9]{32}") private String id; @NotBlank @Size(max=50) private String name; @NotBlank @Pattern(regexp="ACTIVE|DISABLED|DELETED") private String status="ACTIVE"; @Min(0) @Max(10000) private int sortOrder; }
    @Data public static class Edit { @NotBlank @Pattern(regexp="[a-f0-9]{32}") private String id; @NotNull @Min(1) private Integer revision; @NotBlank @Size(max=100) private String name; @NotBlank @Pattern(regexp="[a-f0-9]{32}") private String categoryId; @Size(max=500) private String tags; @Pattern(regexp="DRAFT|PUBLISHED|DISABLED|DELETED") private String status; }
    @Data public static class Use { @Pattern(regexp="[a-f0-9]{32}") private String id; @Pattern(regexp="[a-f0-9]{64}") private String contentHash; @NotNull @Min(1) private Long workspaceId; @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") private String operationId; }
    @Data public static class Resources { @NotNull @Min(1) private Long workspaceId; @NotEmpty @Size(max=100) private java.util.List<@NotNull @Positive Long> resourceIds; }
}
