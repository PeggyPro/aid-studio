package com.aid.canvas.publication.service;
import com.aid.canvas.publication.domain.CanvasPublicationFileIntent;
import com.aid.canvas.publication.mapper.CanvasPublicationWorkMapper;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.config.AidAppConfig;
import com.aid.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;

/** 凭据先于文件写入持久化；附着与业务提交共用事务，回收只处理未附着凭据。 */
@Service @RequiredArgsConstructor public class CanvasPublicationFiles {
    private final CanvasPublicationWorkMapper mapper;
    private final OssTemplate oss;
    private final PlatformTransactionManager transactions;
    @Value("${canvas.publication.storage-root:}") private String storageRoot;
    public Path root() {
        if(storageRoot==null||storageRoot.isBlank())throw new ServiceException("未配置发布私有目录");
        Path root=Path.of(storageRoot).toAbsolutePath().normalize(),pub=Path.of(AidAppConfig.getProfile()).toAbsolutePath().normalize();
        if(root.startsWith(pub)||Files.isSymbolicLink(root))throw new ServiceException("发布目录不属于私有存储");return root;
    }
    public void beginFreeze(String batch) {
        if(!batch.matches("[a-f0-9]{32}"))throw new IllegalArgumentException();
        CanvasPublicationFileIntent f=new CanvasPublicationFileIntent();f.setId(batch);f.setScopeId(batch);f.setKind("PRIVATE");f.setLocation(batch);
        f.setStorageIdentity(cn.hutool.crypto.digest.DigestUtil.sha256Hex(root().toString()));f.setExtension("bin");mapper.intent(f);
    }
    public String upload(String scope,byte[] bytes,String extension) {
        String id=UUID.randomUUID().toString().replace("-","");OssTemplate.ManagedWrite plan=oss.planManagedWrite(id,extension);
        CanvasPublicationFileIntent f=new CanvasPublicationFileIntent();f.setId(id);f.setScopeId(scope);f.setKind("OSS");f.setLocation(plan.location());f.setStorageIdentity(plan.identity());f.setExtension(extension);mapper.intent(f);
        oss.writeManaged(plan,id,extension,bytes);
        // 旧租约线程不得在回收后继续把文件附着到工程。
        new TransactionTemplate(transactions).execute(status->{CanvasPublicationFileIntent current=mapper.lockIntent(id);if(current==null||!"STAGED".equals(current.getStatus()))throw new ServiceException("文件写入凭据已失效");return null;});
        return plan.location();
    }
    public void renew(String scope) {if(mapper.renew(scope)==0)throw new ServiceException("文件准备租约已失效");}
    /** 只允许从已有事务调用，与publication/项目/成功回执原子附着。 */
    public void attach(String scope,Long publication,Long project) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("File attachment requires business transaction");
        List<CanvasPublicationFileIntent> files=mapper.scope(scope);if(files.isEmpty())throw new ServiceException("文件写入凭据不存在");
        for(CanvasPublicationFileIntent f:files)if(mapper.attach(f.getId(),publication,project)!=1)throw new ServiceException("文件准备已超时或正在回收，请重试");
    }
    public void abandon(String scope) {mapper.abandon(scope);for(CanvasPublicationFileIntent f:mapper.scope(scope))collect(f.getId(),true);}
    public int sweep() {int count=0;for(String id:mapper.garbage())if(collect(id,false))count++;return count;}
    private boolean collect(String id,boolean immediate) {
        try {
            CanvasPublicationFileIntent file=new TransactionTemplate(transactions).execute(status->{
                CanvasPublicationFileIntent f=mapper.lockIntent(id);if(f==null||"ATTACHED".equals(f.getStatus()))return null;
                if(!immediate&&f.getExpiresAt().after(new Date()))return null;
                if("DELETED".equals(f.getStatus()))return immediate?f:null;
                return mapper.deleting(id)==1?f:null;
            });
            if(file==null)return false;
            if("PRIVATE".equals(file.getKind())) {
                if(!file.getLocation().matches("[a-f0-9]{32}")||!Objects.equals(file.getStorageIdentity(),cn.hutool.crypto.digest.DigestUtil.sha256Hex(root().toString())))return false;
                Path folder=root().resolve(file.getLocation()).normalize();if(!folder.startsWith(root()))return false;
                if(Files.exists(folder,LinkOption.NOFOLLOW_LINKS))try(var paths=Files.walk(folder)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
            } else {
                if(!oss.deleteManaged(new OssTemplate.ManagedWrite(file.getLocation(),file.getStorageIdentity()),file.getId(),file.getExtension()))return false;
            }
            mapper.deleted(id);return true;
        } catch(Exception ignored) {return false;} // 凭据保留DELETING与重试时间，后台可见，不伪报已回收。
    }
    public int pendingCleanup() {return mapper.pendingCleanup();}
}
