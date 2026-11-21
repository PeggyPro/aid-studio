package com.aid.previz.workspace;

import java.io.IOException;
import java.util.*;

/** Explicit byte-preserving copy into a build snapshot; never executes imported content. */
public final class PrevizSourceImport {
    private PrevizSourceImport() {}
    public static Map<String,byte[]> prepare(PrevizWorkspaceFiles files,PrevizSourceStore sources,
            Long actor,String project,String workspaceVersion,String sourceVersion,Map<String,String> paths)throws IOException {
        if(workspaceVersion==null||!workspaceVersion.matches("[a-f0-9]{64}")||paths==null||paths.isEmpty()||paths.size()>50)
            throw new IllegalArgumentException("必须指定固定文件版本及1至50项复制映射");
        Map<String,byte[]> result=new TreeMap<>();
        if(sourceVersion!=null)result.putAll(sources.read(actor,project,sourceVersion));
        Set<String> targets=new HashSet<>();long total=result.values().stream().mapToLong(v->v.length).sum();
        for(var entry:paths.entrySet()) {
            String target=entry.getValue();
            if(target==null||target.isBlank()||!targets.add(target.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("复制目标重复或为空");
            byte[] bytes=files.readBytes(actor,project,workspaceVersion,entry.getKey());
            if(bytes.length>sources.maximumFileBytes())throw new IllegalArgumentException("构建源码单文件超过2MiB；原工作区文件保留");
            byte[] previous=result.get(target);total+=bytes.length-(previous==null?0:previous.length);
            if(total>8L*1024*1024)throw new IllegalArgumentException("构建快照超过8MiB；未写入源码");
            result.put(target,bytes);
        }
        return Collections.unmodifiableMap(PrevizSourceStore.validateFiles(result));
    }
}
