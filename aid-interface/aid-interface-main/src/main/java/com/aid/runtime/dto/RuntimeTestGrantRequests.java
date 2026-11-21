package com.aid.runtime.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

public final class RuntimeTestGrantRequests {
    private RuntimeTestGrantRequests() { }

    @Data
    public static class Create {
        @NotNull @Positive private Long adminUserId;
        @NotBlank @Pattern(regexp = "SKILL|AGENT") private String memberType;
        @NotNull @Positive private Long memberId;
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") private String draftDigest;
    }
}
