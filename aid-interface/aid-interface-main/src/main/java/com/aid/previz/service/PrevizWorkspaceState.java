package com.aid.previz.service;

import com.aid.previz.mapper.PrevizWorkspaceMapper;
import com.aid.previz.workspace.PrevizSourceStore;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomically publishes source/document pointers after immutable files have been verified. */
public final class PrevizWorkspaceState implements PrevizSourceStore.VersionPointer {
    private final PrevizWorkspaceMapper mapper;
    private final PrevizProjectService access;
    private final TransactionTemplate transactions;
    private final Path sourceRoot;
    public PrevizWorkspaceState(PrevizWorkspaceMapper mapper,PrevizProjectService access,TransactionTemplate transactions,Path sourceRoot){
        this.mapper=mapper;this.access=access;this.transactions=transactions;this.sourceRoot=sourceRoot.toAbsolutePath().normalize();
    }
    public PrevizWorkspaceMapper.Head read(Long actor,String project)throws IOException {
        access.requireRead(actor,project);var head=mapper.read(project);
        if(head==null){head=new PrevizWorkspaceMapper.Head();head.setProjectId(project);head.setRevision(0L);head.setSourceVersion(legacy(project));}
        return head;
    }
    @Override public String current(Long actor,String project,String legacy)throws IOException{return read(actor,project).getSourceVersion();}
    @Override public void compareAndSet(Long actor,String project,String expected,String next)throws IOException {
        publish(actor,project,expected,null,next,null,false,false,null);
    }
    public PrevizWorkspaceMapper.Head publishDocument(Long actor,String project,String expectedSource,String expectedDocument,String document,String title)throws IOException {
        return publish(actor,project,expectedSource,expectedDocument,expectedSource,document,true,false,title);
    }
    public PrevizWorkspaceMapper.Head restore(Long actor,String project,String expectedSource,String expectedDocument,String source,String document)throws IOException {
        return restore(actor,project,expectedSource,expectedDocument,source,document,null);
    }
    public PrevizWorkspaceMapper.Head restore(Long actor,String project,String expectedSource,String expectedDocument,String source,String document,String title)throws IOException {
        requirePublished(actor,project,document);return publish(actor,project,expectedSource,expectedDocument,source,document,true,true,title);
    }
    public void requirePublished(Long actor,String project,String document)throws IOException {
        access.requireRead(actor,project);version(document,false);if(mapper.published(project,document)==0)throw new IOException("DOCUMENT_NOT_FOUND");
    }
    public List<PrevizWorkspaceMapper.Revision> history(Long actor,String project,long before,int limit){
        access.requireRead(actor,project);if(before<1||limit<1||limit>50)throw new IllegalArgumentException("历史分页无效");return mapper.history(project,before,limit);
    }
    private PrevizWorkspaceMapper.Head publish(Long actor,String project,String expectedSource,String expectedDocument,String source,String document,boolean documentChange,boolean restore,String title)throws IOException {
        access.requireWrite(actor,project);version(expectedSource,true);version(source,true);version(expectedDocument,true);version(document,true);
        if(title!=null&&(title.isBlank()||title.length()>120))throw new IllegalArgumentException("项目名称无效");
        String old=legacy(project);
        try{return transactions.execute(status->{
            if(mapper.lockProject(project)==null)throw new IllegalArgumentException("项目不可用");
            mapper.initialize(project,old);var head=mapper.lock(project);access.requireWrite(actor,project);
            if(!Objects.equals(expectedSource,head.getSourceVersion()) || documentChange&&!Objects.equals(expectedDocument,head.getDocumentVersion()))
                throw new UncheckedIOException(new IOException("WORKSPACE_VERSION_CONFLICT"));
            String nextDocument=documentChange?document:head.getDocumentVersion();
            if(title!=null)mapper.title(project,title);
            if(Objects.equals(source,head.getSourceVersion())&&Objects.equals(nextDocument,head.getDocumentVersion()))return head;
            if(head.getRevision()==Long.MAX_VALUE||mapper.update(project,source,nextDocument,head.getRevision())!=1)throw new IllegalStateException("Workspace publication conflict");
            head.setSourceVersion(source);head.setDocumentVersion(nextDocument);head.setRevision(head.getRevision()+1);
            if(documentChange){var entry=new PrevizWorkspaceMapper.Revision();entry.setProjectId(project);entry.setRevision(head.getRevision());entry.setSourceVersion(source);entry.setDocumentVersion(document);entry.setActorId(actor);entry.setOperation(restore?"RESTORE":"SAVE");mapper.insertRevision(entry);}
            return head;
        });}catch(UncheckedIOException failure){throw failure.getCause();}
    }
    private String legacy(String project)throws IOException {
        if(project==null||!project.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("工程标识无效");
        Path dir=sourceRoot.resolve(project),file=dir.resolve("CURRENT");
        if(Files.isSymbolicLink(sourceRoot)||Files.isSymbolicLink(dir)||Files.isSymbolicLink(file))throw new IOException("WORKSPACE_PATH_INVALID");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        if(Files.size(file)!=64)throw new IOException("WORKSPACE_POINTER_INVALID");
        String value=Files.readString(file,StandardCharsets.US_ASCII);version(value,false);return value;
    }
    private static void version(String value,boolean nullable){if(value==null&&nullable)return;if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("版本标识无效");}
}
