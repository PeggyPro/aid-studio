package com.aid.common.storage;

import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.config.AidAppConfig;
import com.aid.common.utils.file.FileUploadUtils;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.env.*;

/** Runs before file-store beans; a configured destination is never replaced by a system-disk fallback. */
public final class LocalStorageBootstrap {
    private final ConfigService configs;
    private final ConfigurableEnvironment environment;
    private final List<LocalStorageAreaProvider> providers;
    private Path activeRoot;
    private Path legacyPublic;
    private List<LocalStorageAreaProvider.Area> areas;
    private long copiedFiles;
    public LocalStorageBootstrap(ConfigService configs,ConfigurableEnvironment environment,List<LocalStorageAreaProvider> providers) {
        this.configs=configs;this.environment=environment;this.providers=providers;
    }
    public void initialize() throws Exception {
        legacyPublic=Path.of(environment.getProperty("aid.profile","./files")).toAbsolutePath().normalize();
        areas=providers.stream().flatMap(p->p.areas(environment,legacyPublic).stream()).toList();
        String configured=configs.getConfigValue("local_storage","root");
        if(configured==null||configured.isBlank()) {
            new AidAppConfig().setProfile(legacyPublic.toString());
            AidAppConfig.setPrivateProfile(null);
            FileUploadUtils.setDefaultBaseDir(legacyPublic.toString());
            return;
        }
        Set<String> properties=new HashSet<>();
        for(var area:areas) {
            Path relative=Path.of(area.relativePath()).normalize();
            if(relative.isAbsolute()||relative.toString().isBlank()||relative.startsWith("..")||!properties.add(area.property()))
                throw new IllegalStateException("本地存储区域声明无效: "+area.property());
        }
        Path target=LocalStorageMigration.validateRoot(configured),oldBase=null;
        boolean complete=Files.isRegularFile(target.resolve("private/.aid-storage.properties"),LinkOption.NOFOLLOW_LINKS);
        String previous=configs.getConfigValue("local_storage","previous_root");
        if(!complete&&previous!=null&&!previous.isBlank()) {
            oldBase=LocalStorageMigration.validateRoot(previous);
        }
        LinkedHashMap<Path,Path> sources=new LinkedHashMap<>();
        if(complete) {
            // The active data volume is authoritative; removed historical source volumes are not required.
        } else if(oldBase!=null) {
            sources.put(oldBase.resolve("public"),target.resolve("public"));
            sources.put(oldBase.resolve("private"),target.resolve("private"));
        } else {
            sources.put(legacyPublic,target.resolve("public"));
            sources.put(legacyPublic.resolveSibling(legacyPublic.getFileName()+"-private"),target.resolve("private"));
            for(var area:areas)if(area.legacyPath()!=null) {
                Path source=area.legacyPath().toAbsolutePath().normalize(),destination=target.resolve("private").resolve(area.relativePath());
                Path existing=sources.putIfAbsent(source,destination);
                if(existing!=null&&!existing.equals(destination)&&Files.exists(source))throw new IllegalStateException("多个存储用途共用来源目录，请先拆分: "+area.property());
            }
        }
        copiedFiles=LocalStorageMigration.migrate(target,sources);
        Map<String,Object> values=new LinkedHashMap<>();values.put("aid.profile",target.resolve("public").toString());
        Path scratch=target.resolve("private/tmp");Files.createDirectories(scratch.resolve("multipart"));Files.createDirectories(scratch.resolve("imageio"));
        values.put("spring.servlet.multipart.location",scratch.resolve("multipart").toString());
        values.put("server.tomcat.basedir",target.resolve("private/tomcat").toString());
        for(var area:areas)values.put(area.property(),target.resolve("private").resolve(area.relativePath()).toString());
        for(var provider:providers)values.putAll(provider.managedProperties(environment,target.resolve("private")));
        environment.getPropertySources().addFirst(new MapPropertySource("aid-managed-local-storage",values));
        new AidAppConfig().setProfile(target.resolve("public").toString());
        AidAppConfig.setPrivateProfile(target.resolve("private").toString());
        FileUploadUtils.setDefaultBaseDir(target.resolve("public").toString());
        activeRoot=target;
    }
    public Path activeRoot(){return activeRoot;}
    public Path publicRoot(){return Path.of(AidAppConfig.getProfile()).toAbsolutePath().normalize();}
    public long copiedFiles(){return copiedFiles;}
    public List<Path> sourceRoots(){
        if(activeRoot!=null)return List.of(activeRoot.resolve("public"),activeRoot.resolve("private"));
        List<Path> sources=new ArrayList<>();sources.add(legacyPublic);sources.add(legacyPublic.resolveSibling(legacyPublic.getFileName()+"-private"));
        for(var area:areas)if(area.legacyPath()!=null)sources.add(area.legacyPath());return sources;
    }
}
