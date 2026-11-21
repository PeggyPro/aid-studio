package com.aid.billing.util;

import com.aid.common.exception.ServiceException;
import com.aid.media.dto.AuthorizedImageAssetSnapshot;
import com.aid.media.dto.MediaVideoGenerateRequest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates current registered image metadata against the exact frozen video image slots. */
public final class FrozenVideoQuoteImages {
    private static final Set<String> SINGLE = Set.of("lastFrameImageUrl", "endImageUrl", "end_image_url");
    private static final Set<String> MULTIPLE = Set.of("referenceImages", "images");
    private FrozenVideoQuoteImages() { }

    public static boolean requireMatching(MediaVideoGenerateRequest request, Map<String,AuthorizedImageAssetSnapshot> images) {
        if(images==null || images.isEmpty())return false;
        Set<String> urls=new LinkedHashSet<>();
        if(request.getImageUrl()!=null && !request.getImageUrl().isBlank())add(urls,request.getImageUrl());
        Map<String,Object> options=request.getOptions();
        if(options!=null) {
            for(String key:SINGLE)if(options.get(key)!=null)add(urls,options.get(key));
            for(String key:MULTIPLE)if(options.get(key)!=null) {
                if(!(options.get(key) instanceof List<?> list))throw rejected();
                for(Object value:list)add(urls,value);
            }
        }
        if(urls.isEmpty() || !urls.equals(images.keySet()))throw rejected();
        for(String url:urls) {
            AuthorizedImageAssetSnapshot image=images.get(url);
            if(image==null)throw rejected();
            image.requireValid(url,"参考图");
        }
        return true;
    }

    public static Map<String,Object> withoutImageOptions(Map<String,Object> options) {
        if(options==null)return null;
        Map<String,Object> remaining=new LinkedHashMap<>(options);
        SINGLE.forEach(remaining::remove);MULTIPLE.forEach(remaining::remove);
        return remaining;
    }

    private static void add(Set<String> urls,Object value) {
        if(!(value instanceof String url) || url.isBlank() || !url.equals(url.trim()))throw rejected();
        urls.add(url);
    }

    private static ServiceException rejected() {
        return new ServiceException("OPERATION_QUOTE_REFERENCE_UNAVAILABLE");
    }
}
