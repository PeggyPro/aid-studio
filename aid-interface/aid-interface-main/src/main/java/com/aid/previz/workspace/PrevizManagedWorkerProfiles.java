package com.aid.previz.workspace;

import com.aid.common.storage.LocalStorageAreaProvider.Area;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.core.env.Environment;

/** Keeps operator connection settings while deriving every host business directory from the active root. */
final class PrevizManagedWorkerProfiles {
    private record Profile(String property,String file,String outputProperty,String output) { }
    private static final List<Profile> PROFILES=List.of(
        new Profile("aid.previz.local-worker.profile","build.json","aid.previz.local-worker.execution-root","previz/build-output"),
        new Profile("aid.previz.resources.profile","resources.json","aid.previz.resources.execution-root","previz/resource-output"));
    static List<Area> areas(Environment env) {
        List<Area> result=new ArrayList<>();
        try {
            for(Profile p:PROFILES) {
                String configured=env.getProperty(p.property());if(configured==null||configured.isBlank())continue;
                JSONObject profile=read(Path.of(configured));
                result.add(new Area(p.outputProperty(),p.output(),directory(profile,"executionRoot")));
                if(p.file().equals("resources.json"))result.add(new Area("aid.previz.resources.intake-root","previz/resource-intake",directory(profile,"intakeRoot")));
            }
        } catch(IOException failure) { throw new UncheckedIOException(failure); }
        return result;
    }
    static Map<String,Object> configure(Environment env,Path privateRoot)throws IOException {
        Map<String,Object> values=new LinkedHashMap<>();Path profiles=privateRoot.resolve("previz/worker-profiles");
        for(Profile p:PROFILES) {
            String configured=env.getProperty(p.property());if(configured==null||configured.isBlank())continue;
            JSONObject operational=read(Path.of(configured));Path effective=profiles.resolve(p.file());
            JSONObject previous=Files.isRegularFile(effective)?read(effective):operational;
            Path output=privateRoot.resolve(p.output());Files.createDirectories(output);
            relocateReceipts(output,directory(previous,"executionRoot"));
            operational.put("sourceRoot",privateRoot.resolve("previz/source").toString());
            operational.put("executionRoot",output.toString());
            if(p.file().equals("resources.json")) {
                Path intake=privateRoot.resolve("previz/resource-intake");Files.createDirectories(intake);operational.put("intakeRoot",intake.toString());
            }
            Files.createDirectories(profiles);
            if(Files.getFileStore(profiles).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(profiles,PosixFilePermissions.fromString("rwx------"));
            write(effective,operational);values.put(p.property(),effective.toString());
        }
        return values;
    }
    private static void relocateReceipts(Path output,Path previous)throws IOException {
        if(output.equals(previous))return;
        try(var files=Files.walk(output,5)) {
            for(Path file:files.filter(p->p.getFileName().toString().equals("result.json")&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()) {
                JSONObject value=read(file),event=value.getJSONObject("event");if(event==null)event=value;
                String old=event.getString("outputDirectory");if(old==null)continue;
                Path stored=Path.of(old).normalize();if(stored.startsWith(output))continue;
                if(!stored.startsWith(previous)||stored.equals(previous))throw new IOException("构建回执目录与迁移来源不一致");
                event.put("outputDirectory",output.resolve(previous.relativize(stored)).toString());write(file,value);
            }
        }
    }
    private static JSONObject read(Path file)throws IOException {
        if(!file.isAbsolute()||Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>1024*1024)throw new IOException("三维进程配置或回执不可读取");
        JSONObject value=JSON.parseObject(Files.readString(file));if(value==null)throw new IOException("三维进程配置或回执为空");return value;
    }
    private static Path directory(JSONObject value,String key)throws IOException {
        String path=value.getString(key);if(path==null||!Path.of(path).isAbsolute())throw new IOException("三维进程业务目录必须为绝对路径");return Path.of(path).normalize();
    }
    private static void write(Path file,JSONObject value)throws IOException {
        Path pending=Files.createTempFile(file.getParent(),".profile-",".tmp");
        try {
            if(Files.getFileStore(pending).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(pending,PosixFilePermissions.fromString("rw-------"));
            Files.writeString(pending,value.toJSONString());try(var channel=FileChannel.open(pending,StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(pending,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(pending);}
    }
}
