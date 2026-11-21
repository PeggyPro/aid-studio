package com.aid.canvas.publication.runtime;

/** 私有快照文件只经成员鉴权网关读取，不向客户端泄漏文件令牌。 */
public final class CanvasPublicationResourceUrl {
    private CanvasPublicationResourceUrl() {}
    public static String resolve(Long resourceId,String storageUrl) {
        if(storageUrl!=null&&storageUrl.startsWith("publication-file:")) {
            if(resourceId==null) throw new IllegalArgumentException("私有快照文件缺少资源编号");
            return "/api/user/canvas/publication/resource/content?resourceId="+resourceId;
        }
        return storageUrl;
    }
}
