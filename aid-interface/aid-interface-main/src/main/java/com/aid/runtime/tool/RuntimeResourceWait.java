package com.aid.runtime.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.Set;

/** Public admission metadata, separate from a business result or an executor receipt. */
public final class RuntimeResourceWait {
    private static final String SCHEMA = "resource-admission-wait/v1";
    private RuntimeResourceWait() { }

    public static String metadata(Long position) {
        if (position != null && position < 1) throw new IllegalArgumentException("排队位置无效");
        return JSONObject.of("schemaVersion",SCHEMA,"queuePosition",position).toJSONString();
    }

    public static boolean isMetadata(String json) {
        try {
            JSONObject value=JSON.parseObject(json);
            if(value==null || !SCHEMA.equals(value.getString("schemaVersion"))
                    || !Set.of("schemaVersion","queuePosition").containsAll(value.keySet()))return false;
            Object raw=value.get("queuePosition");
            return raw==null || raw instanceof Number && new java.math.BigDecimal(raw.toString()).scale()<=0
                    && new java.math.BigDecimal(raw.toString()).compareTo(java.math.BigDecimal.ONE)>=0
                    && new java.math.BigDecimal(raw.toString()).compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE))<=0;
        } catch(RuntimeException invalid){return false;}
    }

    public static Long position(String json) {
        return isMetadata(json)?JSON.parseObject(json).getLong("queuePosition"):null;
    }

    public static String message(Long position) {
        return position==null?"正在准备构建资源":"资源已分配完毕，您正在排队中，当前第 "+position+" 名";
    }
}
