package com.aid.businessscene.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

@Data @TableName("aid_business_entrypoint")
public class AidBusinessEntrypoint {
    @TableId(type=IdType.AUTO) private Long id;
    private String funcCode;
    private String providerCode;
    private String entryKind;
    private String entryCode;
    private String versionId;
    private String packageDigest;
    private Boolean defaultEntry;
    private Integer sortOrder;
    private String updateBy;
    private Date updateTime;
}
