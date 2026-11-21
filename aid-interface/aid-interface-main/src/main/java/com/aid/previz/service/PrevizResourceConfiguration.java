package com.aid.previz.service;

import com.aid.previz.workspace.PrevizFileContentStore;
import com.aid.runtime.mapper.*;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Explicit opt-in; unavailable resource execution never changes existing project/assistant routes. */
@Configuration
@ConditionalOnProperty(prefix="aid.previz.resources",name="enabled",havingValue="true")
public class PrevizResourceConfiguration {
    @Bean public PrevizResourceProcess previzResourceProcess(@Value("${aid.previz.local-worker.node}")String node,
        @Value("${aid.previz.resources.entry}")String entry,@Value("${aid.previz.resources.profile}")String profile,
        @Value("${aid.previz.resources.requests-root}")String root)throws IOException{
        return new PrevizResourceProcess(Path.of(node),Path.of(entry),Path.of(profile),Path.of(root));
    }
    @Bean public PrevizResourceInspectionService previzResourceInspectionService(PrevizProjectService projects,
        PrevizResourceDownloader downloader,PrevizResourceProcess process,PrevizReferenceImageStore images,
        @Value("${aid.previz.source-root}")String root,@Value("${aid.previz.resources.stored-quota-bytes:1073741824}")long quota,
        @Value("${aid.previz.resources.maximum-stored-objects:8192}")int count)throws IOException{
        return new PrevizResourceInspectionService(projects,downloader,process,images,new PrevizFileContentStore(Path.of(root).resolve(".reference-resources"),projects,quota,count,32*1024*1024));
    }
    @Bean public PrevizResourceToolProvider previzResourceToolProvider(PrevizResourceInspectionService resources,PrevizProjectService projects){return new PrevizResourceToolProvider(resources,projects);}
    @Bean public PrevizResourceMediaAdapter previzResourceMediaAdapter(AidRuntimeRunMapper runs,AidRuntimeRunStepMapper steps,
        PrevizImageReferenceAdapter images,PrevizProjectService projects){return new PrevizResourceMediaAdapter(runs,steps,images,projects);}
}
