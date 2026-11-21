package com.aid.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter @AllArgsConstructor
public enum DirectorRecordingResolutionEnum {
    FULL_HD("1920x1080", "1080p · 1920 × 1080"),
    HD("1280x720", "720p · 1280 × 720");
    private final String value;
    private final String desc;
}
