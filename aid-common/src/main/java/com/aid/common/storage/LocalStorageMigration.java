package com.aid.common.storage;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

/** Cold-start migration: verify each copy before activating; never delete original business files. */
public final class LocalStorageMigration {
    private LocalStorageMigration() { }
    public static Path validateRoot(String value) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("本地数据目录不能为空");
        Path path = Path.of(value).normalize();
        if (!path.isAbsolute() || path.getParent() == null) throw new IOException("本地数据目录必须是独立的绝对路径");
        for (Path at=path; at!=null; at=at.getParent()) {
            if (Files.exists(at,LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes=Files.readAttributes(at,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(attributes.isSymbolicLink()||attributes.isOther())throw new IOException("本地数据目录不能经过链接或特殊目录");
            }
        }
        for (String forbidden : List.of("/etc", "/proc", "/sys", "/dev", "/bin", "/sbin", "/usr"))
            if (path.startsWith(Path.of(forbidden))) throw new IOException("不能使用系统目录保存业务文件");
        String systemRoot=System.getenv("SystemRoot");
        if(systemRoot!=null&&path.startsWith(Path.of(systemRoot).toAbsolutePath().normalize()))throw new IOException("不能使用系统目录保存业务文件");
        return path;
    }
    public static void preflight(Path target, Collection<Path> sources) throws IOException {
        validateRoot(target.toString());
        for (Path source : sources) {
            Path old=source.toAbsolutePath().normalize();
            if (target.startsWith(old) || old.startsWith(target)) throw new IOException("新目录不能与现有存储目录重叠");
        }
        Files.createDirectories(target);
        try (var entries=Files.list(target)) {
            if (entries.findAny().isPresent()) throw new IOException("请选择空目录，避免覆盖已有文件");
        }
        long[] required={0};
        for(Path source:sources)if(Files.exists(source,LinkOption.NOFOLLOW_LINKS))Files.walkFileTree(source,new SimpleFileVisitor<>(){
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{
                if(!attrs.isRegularFile()||Files.isSymbolicLink(file))throw new IOException("来源含不受支持的文件类型");
                required[0]=Math.addExact(required[0],attrs.size());return FileVisitResult.CONTINUE;
            }
        });
        if(required[0]>Files.getFileStore(target).getUsableSpace())throw new IOException("目标目录可用空间不足");
        Path probe=Files.createTempFile(target,".aid-write-check-",".tmp");
        try (FileChannel channel=FileChannel.open(probe,StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{65,73,68})); channel.force(true);
        } finally { Files.deleteIfExists(probe); }
    }
    public static long migrate(Path target, LinkedHashMap<Path,Path> sources) throws IOException {
        validateRoot(target.toString());
        for (Path source:sources.keySet()) {
            Path old=source.toAbsolutePath().normalize();
            if(target.startsWith(old)||old.startsWith(target))throw new IOException("迁移目录重叠");
        }
        Path privateRoot=target.resolve("private"), marker=privateRoot.resolve(".aid-storage.properties");
        if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS)) {
            if(Files.isSymbolicLink(marker))throw new IOException("迁移状态文件无效");
            Properties done=new Properties();try(var in=Files.newInputStream(marker)){done.load(in);}
            if(!target.toString().equals(done.getProperty("root")))throw new IOException("目录归属不匹配");
            return Long.parseLong(done.getProperty("files","0"));
        }
        Path pending=privateRoot.resolve(".aid-storage.pending");
        Properties plan=new Properties();
        if(!Files.exists(pending,LinkOption.NOFOLLOW_LINKS)) {
            preflight(target,sources.keySet());Files.createDirectories(privateRoot);makePrivate(privateRoot);
            plan.setProperty("root",target.toString());plan.setProperty("sources",sources.toString());
            plan.setProperty("stagingId",UUID.randomUUID().toString().replace("-",""));writeState(pending,plan);
        } else {
            if(Files.isSymbolicLink(pending))throw new IOException("迁移状态文件无效");
            try(var in=Files.newInputStream(pending)){plan.load(in);}
            if(!target.toString().equals(plan.getProperty("root"))||!sources.toString().equals(plan.getProperty("sources")))throw new IOException("迁移来源已变化");
        }
        String stagingId=plan.getProperty("stagingId","");
        if(!stagingId.matches("[a-f0-9]{32}"))throw new IOException("迁移暂存身份无效");
        Path staging=privateRoot.resolve(".aid-migrate-"+stagingId);validateRoot(staging.toString());
        Files.createDirectories(staging);makePrivate(staging);
        // Resume after process/power loss without accumulating partial copies or deleting business files.
        try(var partials=Files.newDirectoryStream(staging)) {
            for(Path partial:partials) {
                if(!Files.isRegularFile(partial,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(partial)
                        ||!partial.getFileName().toString().matches("[.]part-[A-Za-z0-9_-]+[.]tmp"))throw new IOException("迁移暂存文件类型无效");
                Files.delete(partial);
            }
        }
        long[] count={0};
        for(var mapping:sources.entrySet()) {
            Path source=mapping.getKey().toAbsolutePath().normalize(),destination=mapping.getValue().normalize();
            if(!destination.startsWith(target))throw new IOException("迁移目标越界");
            if(!Files.exists(source,LinkOption.NOFOLLOW_LINKS))continue;
            Files.walkFileTree(source,new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException {
                    if(Files.isSymbolicLink(dir))throw new IOException("存储目录含符号链接");
                    Path to=destination.resolve(source.relativize(dir));validateRoot(to.toString());
                    Files.createDirectories(to);return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir,IOException failure)throws IOException {
                    if(failure!=null)throw failure;
                    forceDirectory(destination.resolve(source.relativize(dir)));return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                    if(!attrs.isRegularFile()||Files.isSymbolicLink(file))throw new IOException("存储文件类型不受支持");
                    Path relative=source.relativize(file);
                    if(relative.getNameCount()==1&&Set.of(".aid-storage.properties",".aid-storage.pending").contains(relative.toString()))return FileVisitResult.CONTINUE;
                    Path to=destination.resolve(source.relativize(file));
                    if(Files.exists(to,LinkOption.NOFOLLOW_LINKS)) {
                        if(Files.isSymbolicLink(to)||!Arrays.equals(digest(file),digest(to)))throw new IOException("迁移目标文件冲突");
                    } else {
                        Path temp=Files.createTempFile(staging,".part-",".tmp");
                        try {
                            Files.copy(file,temp,StandardCopyOption.REPLACE_EXISTING);
                            if(!Arrays.equals(digest(file),digest(temp)))throw new IOException("迁移文件校验失败");
                            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
                            Files.move(temp,to,StandardCopyOption.ATOMIC_MOVE);
                        } finally {Files.deleteIfExists(temp);}
                    }
                    count[0]++;return FileVisitResult.CONTINUE;
                }
            });
        }
        Files.createDirectories(target.resolve("public"));Files.createDirectories(privateRoot);
        makePrivate(privateRoot);
        Files.delete(staging);
        Properties done=new Properties();done.setProperty("root",target.toString());done.setProperty("files",Long.toString(count[0]));
        writeState(marker,done);Files.delete(pending);return count[0];
    }
    private static byte[] digest(Path file)throws IOException {
        try {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}return digest.digest();}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void writeState(Path target,Properties state)throws IOException {
        Path temporary=Files.createTempFile(target.getParent(),".aid-state-",".tmp");
        try {
            try(var output=Files.newOutputStream(temporary,StandardOpenOption.WRITE)){state.store(output,"AID local storage migration");}
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(target.getParent());
        } finally {Files.deleteIfExists(temporary);}
    }
    private static void makePrivate(Path path)throws IOException {
        if(Files.getFileStore(path).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));
    }
    private static void forceDirectory(Path path)throws IOException {
        if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"))try(var channel=FileChannel.open(path,StandardOpenOption.READ)){channel.force(true);}
    }
}
