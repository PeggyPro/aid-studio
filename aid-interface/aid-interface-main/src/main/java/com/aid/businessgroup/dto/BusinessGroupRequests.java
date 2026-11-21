package com.aid.businessgroup.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

public final class BusinessGroupRequests {
    private BusinessGroupRequests() { }

    @Data public static class Page {
        @Min(1) private Integer pageNum = 1;
        @Min(1) @Max(100) private Integer pageSize = 20;
        @Size(max = 64) private String keyword;
        @Size(max = 64) private String parentCode;
        private Boolean enabledOnly;
    }

    @Data public static class Id {
        @NotNull @Positive private Long id;
    }

    @Data public static class Save {
        private Long id;
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9._-]{1,63}") private String groupCode;
        @Positive private Long parentId;
        @NotBlank @Size(max = 100) private String name;
        @Size(max = 500) private String iconUrl;
        @Size(max = 1000) private String description;
        @Min(0) @Max(1000000) private Integer sortOrder = 0;
        @NotBlank @Pattern(regexp = "PUBLIC|PRIVATE") private String visibility = "PRIVATE";
        @NotBlank @Pattern(regexp = "0|1") private String status = "1";
    }

    @Data public static class MemberPage {
        @NotNull @Positive private Long groupId;
        @Min(1) private Integer pageNum = 1;
        @Min(1) @Max(100) private Integer pageSize = 20;
    }

    @Data public static class MemberSave {
        @NotNull @Positive private Long groupId;
        @NotBlank @Pattern(regexp = "SKILL|AGENT") private String memberType;
        @NotNull @Positive private Long memberId;
        @Min(0) @Max(1000000) private Integer sortOrder = 0;
    }

    @Data public static class MemberRemove {
        @NotNull @Positive private Long groupId;
        @NotBlank @Pattern(regexp = "SKILL|AGENT") private String memberType;
        @NotNull @Positive private Long memberId;
    }
}
