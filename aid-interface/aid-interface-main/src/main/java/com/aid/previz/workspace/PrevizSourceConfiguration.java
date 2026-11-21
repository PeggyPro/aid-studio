package com.aid.previz.workspace;

import com.aid.previz.service.PrevizProjectService;
import com.aid.previz.service.PrevizWorkspaceState;
import com.aid.previz.service.PrevizDocumentStore;
import com.aid.previz.service.PrevizDocumentValidator;
import com.aid.previz.service.PrevizArtifactService;
import com.aid.previz.mapper.PrevizWorkspaceMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 配置独立三维工程的私有源码存储。 */
@Configuration
@ConditionalOnProperty(prefix = "aid.previz", name = "enabled", havingValue = "true")
public class PrevizSourceConfiguration
{
    @Bean
    public PrevizStoragePolicy previzStoragePolicy(com.aid.core.service.ISysConfigService configs) {
        return (key,fallback)->key.parse(configs.selectConfigByKey(key.configKey()),fallback);
    }
    @Bean
    public PrevizWorkspaceFiles previzWorkspaceFiles(@Value("${aid.previz.source-root}") String root,PrevizProjectService access,
            @Value("${aid.previz.files.maximum-files:5000}")int count,@Value("${aid.previz.files.active-quota-bytes:536870912}")long quota,
            @Value("${aid.previz.files.stored-quota-bytes:1073741824}")long storedQuota,
            @Value("${aid.previz.files.maximum-stored-objects:8192}")int storedObjects,PrevizStoragePolicy policy)throws IOException {
        return new PrevizWorkspaceFiles(Path.of(root).resolve(".workspace-files"),access,count,quota,storedQuota,storedObjects,policy);
    }
    @Bean
    public PrevizWorkspaceState previzWorkspaceState(PrevizWorkspaceMapper mapper,PrevizProjectService access,
            PlatformTransactionManager manager,@Value("${aid.previz.source-root}") String root) {
        return new PrevizWorkspaceState(mapper,access,new TransactionTemplate(manager),Path.of(root));
    }
    @Bean
    public PrevizDocumentStore previzDocumentStore(@Value("${aid.previz.source-root}") String root,
            PrevizProjectService access,PrevizProjectBlobStore blobs,PrevizArtifactService artifacts,
            PrevizSourceStore sources,PrevizWorkspaceState state,
            @Value("${aid.previz.documents.maximum-bytes:16777216}") int maximumBytes,
            @Value("${aid.previz.documents.project-quota-bytes:536870912}") long quota,
            @Value("${aid.previz.documents.maximum-versions:4096}") int versions,PrevizStoragePolicy policy)throws IOException {
        return new PrevizDocumentStore(Path.of(root).resolve(".documents"),access,new PrevizDocumentValidator(blobs,artifacts),sources,state,maximumBytes,quota,versions,policy);
    }
    @Bean
    public PrevizProjectBlobStore previzProjectBlobStore(
            @Value("${aid.previz.source-root}") String sourceRoot, PrevizProjectService access,
            @Value("${aid.previz.assets.project-quota-bytes:2147483648}") long quota,
            @Value("${aid.previz.assets.minimum-free-bytes:268435456}") long free,
            @Value("${aid.previz.assets.maximum-pending-uploads:8}") int pending,
            @Value("${aid.previz.assets.maximum-project-assets:4096}") int assets,
            @Value("${aid.previz.assets.maximum-upload-records:32768}") int records,PrevizStoragePolicy policy) throws IOException {
        if(sourceRoot==null||sourceRoot.isBlank()||!Path.of(sourceRoot).isAbsolute())throw new IllegalArgumentException("资产根目录必须为私有绝对路径");
        return new PrevizProjectBlobStore(Path.of(sourceRoot).resolve(".project-assets"),access,quota,free,pending,assets,records,policy);
    }
    @Bean
    public PrevizSourceStore previzSourceStore(
            @Value("${aid.previz.source-root}") String sourceRoot, PrevizProjectService access,PrevizWorkspaceState state,
            @Value("${aid.previz.sources.project-quota-bytes:536870912}") long quota,
            @Value("${aid.previz.sources.maximum-versions:4096}") int versions,PrevizStoragePolicy policy) throws IOException
    {
        if (sourceRoot == null || sourceRoot.isBlank() || !Path.of(sourceRoot).isAbsolute())
            throw new IllegalArgumentException("必须配置独立的绝对源码目录，且不得对外静态发布");
        return new PrevizSourceStore(Path.of(sourceRoot), access,state,quota,versions,policy);
    }
}
