package com.aid.canvas.runtime;

import com.aid.canvas.publication.runtime.CanvasPublicationResourceUrl;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.utils.spring.SpringUtils;
import java.util.Set;

/** 在已完成项目/资源授权的响应投影阶段签名，签名地址不写回资源记录。 */
public final class CanvasModelAccessUrl {
    private CanvasModelAccessUrl() { }
    public static String resolve(Long id,String storage,String mediaType) {
        String value=CanvasPublicationResourceUrl.resolve(id,storage);
        if(value==null||mediaType==null||value.startsWith("/api/user/canvas/publication/")||!Set.of("MODEL","MODEL_DEP","SCENE_PACKAGE").contains(mediaType))return value;
        return SpringUtils.getBean(OssTemplate.class).getSignedUrl(value,900);
    }
}
