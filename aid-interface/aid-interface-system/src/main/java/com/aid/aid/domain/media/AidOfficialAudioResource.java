package com.aid.aid.domain.media;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** 共享上游账号中的文件、歌曲和自定义音色保持调用用户归属。 */
@Data
@TableName("aid_official_audio_resource")
public class AidOfficialAudioResource {
    @TableId(type=IdType.AUTO) private Long id;
    private Long providerId;
    private Long userId;
    private String resourceKind;
    private String resourceId;
    private String purpose;
    private java.util.Date createTime;
}
