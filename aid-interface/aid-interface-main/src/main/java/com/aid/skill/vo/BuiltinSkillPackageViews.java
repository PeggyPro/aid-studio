package com.aid.skill.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

public final class BuiltinSkillPackageViews
{
    private BuiltinSkillPackageViews() { }

    @Data
    public static class Dependency
    {
        private String skillCode;
        private String name;
        private String versionCode;
        private String sourceDigest;
        private Long skillId;
        private Long versionId;
        private Long currentVersionId;
        private Long draftId;
        private String draftDigest;
        private String state;
        private String message;
    }

    @Data
    public static class Status
    {
        private int total;
        private int ready;
        private int drafts;
        private int missing;
        private int conflicts;
        private List<Dependency> items = new ArrayList<>();
    }
}
