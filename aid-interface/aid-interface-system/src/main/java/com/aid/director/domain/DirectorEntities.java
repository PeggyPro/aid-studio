package com.aid.director.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Date;

/** 机位资源与可恢复录制业务元数据。 */
public final class DirectorEntities {
    private DirectorEntities() { }

    @Data
    public static class Audit {
        @TableId(type = IdType.INPUT)
        private Long id;
        private String createBy;
        private String updateBy;
        private Date createTime;
        private Date updateTime;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("aid_director_scene")
    public static class Scene extends Audit {
        private Long projectId;
        private Long workspaceId;
        private String scopeKey;
        private String sceneKey;
        private String name;
        private Long revision;
        private String objectKey;
        private String contentHash;
        private Integer byteSize;
        private String lastRequestId;
        private String lastRequestHash;
        private Integer deleted;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("aid_director_camera")
    public static class Camera extends Audit {
        private Long sceneId;
        private String cameraKey;
        private Long controlFence;
        private String ownerRoomId;
        private String ownerRoomEpoch;
        private Date ownerExpiresAt;
        private Integer deleted;
        private Boolean locked;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("aid_director_take")
    public static class Take extends Audit {
        private Long projectId;
        private Long workspaceId;
        private Long sceneId;
        private Long cameraRowId;
        private Long hostUserId;
        private String originRoomId;
        private String roomId;
        private String roomEpoch;
        private Long controlFence;
        private Long contextVersion;
        private String startRequestId;
        private String startRequestHash;
        private String stopRequestId;
        private Boolean hostStopped;
        private String state;
        private Long durableFrameSeq;
        private Integer chunkCount;
        private Integer frameCount;
        private Long durationMs;
        private String manifestKey;
        private String contentHash;
        private Long sceneRevisionAtStart;
        private Long appliedRevision;
        private String appliedClipId;
        private String applyRequestHash;
        private Boolean recovered;
        private Date lastActivityAt;
        private Date expireAt;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("aid_director_take_chunk")
    public static class Chunk extends Audit {
        private Long takeId;
        private Integer chunkNo;
        private Long firstFrameSeq;
        private Long lastFrameSeq;
        private Long startMs;
        private Long endMs;
        private Integer frameCount;
        private Integer byteSize;
        private String contentHash;
        private String objectKey;
    }
}
