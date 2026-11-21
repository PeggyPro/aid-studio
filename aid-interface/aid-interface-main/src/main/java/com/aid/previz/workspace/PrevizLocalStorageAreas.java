package com.aid.previz.workspace;

import com.aid.common.storage.LocalStorageAreaProvider;
import java.nio.file.Path;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Registers private module areas without exposing commercial implementation in public modules. */
@Component
public final class PrevizLocalStorageAreas implements LocalStorageAreaProvider {
    public List<Area> areas(Environment env,Path profile){
        Path old=profile.resolveSibling(profile.getFileName()+"-private");
        List<Area> result=new ArrayList<>(List.of(
            area(env,"aid.previz.source-root","previz/source",old.resolve("previz/source")),
            area(env,"aid.previz.local-worker.requests-root","previz/build-requests",old.resolve("previz/build-requests")),
            area(env,"aid.previz.resources.requests-root","previz/resource-requests",old.resolve("previz/resource-requests")),
            area(env,"director.vcam.storage-root","director-objects",Path.of(System.getProperty("user.home"),".aid/director-objects"))));
        result.addAll(PrevizManagedWorkerProfiles.areas(env));return result;
    }
    public Map<String,Object> managedProperties(Environment env,Path privateRoot)throws java.io.IOException{return PrevizManagedWorkerProfiles.configure(env,privateRoot);}
    private Area area(Environment e,String key,String relative,Path fallback){String value=e.getProperty(key);return new Area(key,relative,value==null||value.isBlank()?fallback:Path.of(value).toAbsolutePath().normalize());}
    public Set<String> dependentBeans(){return Set.of("previzSourceConfiguration","previzResourceConfiguration","previzEditorStore","previzSceneEditService","previzReferenceImageStore","previzComputeDispatchService","previzPreviewService","previzLocalBuildService","directorProperties");}
}
