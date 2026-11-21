package com.aid.compute;

import java.net.URI;
import java.util.Set;
import java.util.List;
import java.util.HashSet;

/** Operator configuration only. No executable command or credential value is accepted. */
public record ComputeNodeConfiguration(String name, String endpoint, String poolCode, String credentialRef,
        Set<String> engines, ComputeResourcePolicy.Limits limits, int executionTimeoutSeconds,
        int queueLimit, String storageAlias,List<Slot> slots,String tlsCaPem) {
    public ComputeNodeConfiguration(String name,String endpoint,String poolCode,String credentialRef,Set<String> engines,
            ComputeResourcePolicy.Limits limits,int executionTimeoutSeconds,int queueLimit,String storageAlias,List<Slot> slots){
        this(name,endpoint,poolCode,credentialRef,engines,limits,executionTimeoutSeconds,queueLimit,storageAlias,slots,null);
    }
    public record Slot(String engine,String taskKind,ComputeResourcePolicy.Resources resources,int timeoutSeconds){
        public Slot{new ComputeResourcePolicy.Request(engine,taskKind,resources);
            if(timeoutSeconds<1||timeoutSeconds>86400)throw new IllegalArgumentException("槽位超时必须为1至86400秒");}
    }
    public ComputeNodeConfiguration(String name,String endpoint,String poolCode,String credentialRef,Set<String> engines,
            ComputeResourcePolicy.Limits limits,int executionTimeoutSeconds,int queueLimit,String storageAlias){
        this(name,endpoint,poolCode,credentialRef,engines,limits,executionTimeoutSeconds,queueLimit,storageAlias,List.of());
    }
    public ComputeNodeConfiguration {
        tlsCaPem=ComputeTlsSupport.normalize(tlsCaPem);
        if (name == null || name.isBlank() || name.length() > 100) throw new IllegalArgumentException("请填写不超过100字的节点名称");
        name = name.trim();
        ComputeResourcePolicy.key(poolCode); ComputeResourcePolicy.key(storageAlias);
        if (credentialRef != null && !credentialRef.isBlank()) ComputeResourcePolicy.key(credentialRef);
        else credentialRef = null;
        endpoint=normalizeEndpoint(endpoint);
        if (engines == null || engines.isEmpty() || engines.size() > 100) throw new IllegalArgumentException("至少选择一种引擎");
        engines.forEach(ComputeResourcePolicy::key); engines = Set.copyOf(engines);
        if (limits == null || !engines.containsAll(limits.engineConcurrent().keySet())) throw new IllegalArgumentException("并发规则包含未配置的引擎");
        if (executionTimeoutSeconds < 1 || executionTimeoutSeconds > 86400) throw new IllegalArgumentException("执行超时必须为1至86400秒");
        if (queueLimit < 0 || queueLimit > 100000) throw new IllegalArgumentException("排队上限必须为0至100000");
        slots=slots==null?List.of():List.copyOf(slots);
        if(slots.size()>500)throw new IllegalArgumentException("槽位规格数量超过限制");
        var identities=new HashSet<String>();
        for(var slot:slots){
            if(!engines.contains(slot.engine())||!identities.add(slot.engine()+"\0"+slot.taskKind()))throw new IllegalArgumentException("槽位引擎无效或规格重复");
            if(!limits.capacity().accommodates(slot.resources()))throw new IllegalArgumentException("单个槽位资源超过机器配置额度");
            if(slot.timeoutSeconds()>executionTimeoutSeconds)throw new IllegalArgumentException("槽位超时不能超过节点执行超时上限");
        }
    }
    public static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.length() > 500) throw new IllegalArgumentException("节点服务地址无效");
        URI uri;
        try { uri = URI.create(endpoint); } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("节点服务地址无效"); }
        boolean local = Set.of("127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535
                || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
            throw new IllegalArgumentException("节点地址必须是HTTPS服务根地址；仅127.0.0.1或[::1]测试允许HTTP，不可包含账号、路径、查询或片段");
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length()-1) : endpoint;
    }
}
