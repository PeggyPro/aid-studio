package com.aid.canvas.model;

import com.aid.common.aid.oss.annotation.MediaUrl;
import lombok.Data;

/** 后台受管模型的不可变文件版本。 */
@Data
public class CanvasLibraryModel {
    private String id;
    private String modelId;
    private Integer versionNo;
    private String name;
    private String categoryId;
    private String categoryName;
    private String tags;
    private String status;
    @MediaUrl private String storagePath;
    @MediaUrl private String thumbnailPath;
    private String thumbnailUrl;
    private Long fileSize;
    private String contentHash;
    private String metadataJson;
    private Integer revision;
    private String createTime;
}
