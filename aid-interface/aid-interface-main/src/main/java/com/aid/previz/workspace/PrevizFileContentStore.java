package com.aid.previz.workspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Immutable auxiliary workspace bytes. This store never executes or publishes files as web assets. */
public final class PrevizFileContentStore {
    public static final int MAX_FILE_BYTES=10*1024*1024;
    private final Path root;
    private final PrevizWorkspaceAccess access;
    private final long maximumStoredBytes;
    private final int maximumObjects;
    private final int maximumFileBytes;
    private final PrevizStoragePolicy policy;
    private static final java.util.concurrent.locks.ReentrantLock[] LOCKS=new java.util.concurrent.locks.ReentrantLock[64];
    static{for(int i=0;i<LOCKS.length;i++)LOCKS[i]=new java.util.concurrent.locks.ReentrantLock();}
    public PrevizFileContentStore(Path root,PrevizWorkspaceAccess access)throws IOException {
        this(root,access,1024L*1024*1024,8192);
    }
    public PrevizFileContentStore(Path root,PrevizWorkspaceAccess access,long maximumStoredBytes,int maximumObjects)throws IOException {
        this(root,access,maximumStoredBytes,maximumObjects,MAX_FILE_BYTES);
    }
    public PrevizFileContentStore(Path root,PrevizWorkspaceAccess access,long maximumStoredBytes,int maximumObjects,int maximumFileBytes)throws IOException {
        this(root,access,maximumStoredBytes,maximumObjects,maximumFileBytes,PrevizStoragePolicy.DEFAULT);
    }
    public PrevizFileContentStore(Path root,PrevizWorkspaceAccess access,long maximumStoredBytes,int maximumObjects,int maximumFileBytes,PrevizStoragePolicy policy)throws IOException {
        if(root==null||!root.isAbsolute()||access==null)throw new IllegalArgumentException("必须配置私有文件根目录与项目权限");
        if(maximumFileBytes<1||maximumFileBytes>32*1024*1024||maximumStoredBytes<maximumFileBytes||maximumObjects<1)throw new IllegalArgumentException("文件存储容量配置无效");
        Files.createDirectories(root);rejectLink(root);this.root=root.toRealPath();this.access=access;
        this.maximumStoredBytes=maximumStoredBytes;this.maximumObjects=maximumObjects;this.maximumFileBytes=maximumFileBytes;
        this.policy=Objects.requireNonNull(policy);
    }
    public String put(Long actor,String project,byte[] data)throws IOException {
        access.requireWrite(actor,project);if(data==null||data.length>maximumFileBytes)throw new IllegalArgumentException("单文件超过存储限制");
        Path directory=project(project,true);var mutex=LOCKS[Math.floorMod(directory.hashCode(),LOCKS.length)];mutex.lock();
        try{Path lock=directory.resolve(".content-lock");rejectLink(lock);try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var ignored=channel.lock()){
            access.requireWrite(actor,project);return putLocked(actor,project,data);
        }}finally{mutex.unlock();}
    }
    private String putLocked(Long actor,String project,byte[] data)throws IOException {
        String digest=digest(data);Path directory=project(project,true),target=directory.resolve(digest);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){verify(target,digest,data.length);return digest;}
        long maximumStoredBytes=policy.limit(PrevizStoragePolicy.Key.STORED_BYTES,this.maximumStoredBytes);
        long maximumObjects=policy.limit(PrevizStoragePolicy.Key.STORED_OBJECTS,this.maximumObjects);
        if(data.length>maximumStoredBytes)throw new IOException("WORKSPACE_STORAGE_QUOTA_EXCEEDED");
        long occupied=0;int count=0;
        try(var files=Files.list(directory)){var iterator=files.iterator();while(iterator.hasNext()){Path file=iterator.next();rejectLink(file);if(file.getFileName().toString().equals(".content-lock"))continue;if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||++count>=maximumObjects)throw new IOException("WORKSPACE_STORAGE_COUNT_EXCEEDED");occupied=Math.addExact(occupied,Files.size(file));if(occupied+data.length>maximumStoredBytes)throw new IOException("WORKSPACE_STORAGE_QUOTA_EXCEEDED");}}
        Path pending=directory.resolve(".pending-"+UUID.randomUUID());
        try {
            try(var channel=FileChannel.open(pending,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                ByteBuffer buffer=ByteBuffer.wrap(data);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            access.requireWrite(actor,project);
            try{Files.createLink(target,pending);}catch(FileAlreadyExistsException replay){verify(target,digest,data.length);}
            verify(target,digest,data.length);return digest;
        }finally{Files.deleteIfExists(pending);}
    }
    public byte[] read(Long actor,String project,String digest,int size)throws IOException {
        access.requireRead(actor,project);if(digest==null||!digest.matches("[a-f0-9]{64}")||size<0||size>maximumFileBytes)throw new IllegalArgumentException("文件引用无效");
        Path file=project(project,false).resolve(digest);verify(file,digest,size);
        byte[] data;try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){data=input.readNBytes(maximumFileBytes+1);}
        if(data.length!=size||!digest(data).equals(digest))throw new IOException("WORKSPACE_CONTENT_CHANGED");access.requireRead(actor,project);return data;
    }
    private Path project(String project,boolean create)throws IOException {
        if(project==null||!project.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("工程标识无效");
        rejectLink(root);Path directory=root.resolve(project);rejectLink(directory);
        if(create&&!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))try{Files.createDirectory(directory);}catch(FileAlreadyExistsException concurrent){rejectLink(directory);}
        if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("WORKSPACE_DIRECTORY_INVALID");return directory;
    }
    private static void rejectLink(Path path)throws IOException {
        if(Files.isSymbolicLink(path)||Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!path.toRealPath().equals(path.toAbsolutePath().normalize()))throw new IOException("WORKSPACE_PATH_INVALID");
    }
    private void verify(Path path,String expected,int size)throws IOException {
        rejectLink(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=size)throw new IOException("WORKSPACE_CONTENT_UNAVAILABLE");
        MessageDigest digest=sha();byte[] buffer=new byte[65536];int total=0;
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){for(int count;(count=input.read(buffer))>=0;){total+=count;if(total>maximumFileBytes)throw new IOException("WORKSPACE_FILE_TOO_LARGE");digest.update(buffer,0,count);}}
        if(total!=size||!HexFormat.of().formatHex(digest.digest()).equals(expected))throw new IOException("WORKSPACE_CONTENT_CHANGED");
    }
    public static String digest(byte[] data){return HexFormat.of().formatHex(sha().digest(data));}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
