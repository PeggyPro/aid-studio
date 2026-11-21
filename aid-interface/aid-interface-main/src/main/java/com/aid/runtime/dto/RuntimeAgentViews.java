package com.aid.runtime.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Redacted admin projections for Runtime Agent identities, drafts and versions. */
public final class RuntimeAgentViews
{
    private RuntimeAgentViews()
    {
    }

    @Data
    public static class AgentSummary
    {
        private Long id;
        private String agentCode;
        private String name;
        private String description;
        private String iconUrl;
        private String visibility;
        private String agentFamily;
        private Long currentVersionId;
        private String status;
        private String delFlag;
        private String runtimeAvailability;
        private String runtimeUnavailableReason;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private Date updateTime;
    }

    /** Authoritative receipt returned by an identity status mutation. */
    @Data
    public static class StatusView
    {
        private Long id;
        private String status;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private Date updateTime;
    }

    /** Receipt for reversible stable-identity deletion and restoration. */
    @Data
    public static class IdentityStateView
    {
        private Long id;
        private String status;
        private String delFlag;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private Date updateTime;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class DraftView extends RuntimeAgentRequests.DraftPayload
    {
        private Long draftId;
        private String draftDigest;
        private String agentCode;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private Date updateTime;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class VersionView extends RuntimeAgentRequests.DraftPayload
    {
        private Long id;
        private String versionCode;
        private String publishStatus;
        private String packageDigest;
        private String status;
        private boolean current;
        private String runtimeAvailability;
        private String runtimeUnavailableReason;
        private String createBy;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private Date createTime;
    }

    @Data
    public static class ValidationView
    {
        private boolean valid;
        private String draftDigest;
        private List<Issue> errors = new ArrayList<>();
        private List<Issue> warnings = new ArrayList<>();
    }

    public record Issue(String field, String message)
    {
    }

    @Data
    public static class SkillOption
    {
        private Long skillId;
        private Long skillVersionId;
        private String skillCode;
        private String name;
        private String versionCode;
        private String packageDigest;
        private String invocationScope;
    }

    @Data
    public static class BuiltinImportView
    {
        private String agentCode;
        private Long agentId;
        private Long draftId;
        private String draftDigest;
        private String draftPackageVersionCode;
        private String builtinPackageVersionCode;
        private String state;
        private String message;
    }

    @Data
    public static class ModuleBindingView
    {
        private String moduleCode;
        private String expectedAgentCode;
        private Long agentId;
        private Long activeVersionId;
        private Long bindingRevision;
        private String state;
        private String runtimeAvailability;
        private String runtimeUnavailableReason;
    }

}
