package com.aid.canvas.publication.service;
import com.alibaba.fastjson2.*;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service @RequiredArgsConstructor public class CanvasPublicationBundleService {
    private final ObjectProvider<CanvasPublicationPort> ports;
    private final CanvasModelBundleInspector inspector;
    private final CanvasPublicationFiles ledger;
    private final MediaUrlResolver urls;
    private final PlatformTransactionManager transactions;
    @org.springframework.beans.factory.annotation.Value("${canvas.publication.model-max-concurrent-uploads:1}") private int maxConcurrentUploads=1;
    private java.util.concurrent.Semaphore uploadSlots=new java.util.concurrent.Semaphore(1);
    @jakarta.annotation.PostConstruct void configure(){if(maxConcurrentUploads<1||maxConcurrentUploads>4)throw new IllegalArgumentException("Invalid model upload concurrency");uploadSlots=new java.util.concurrent.Semaphore(maxConcurrentUploads);}
    public JSONObject upload(Long workspace,Long user,String operation,String mediaType,String root,List<String> paths,List<MultipartFile> parts) throws Exception {
        return upload(workspace,user,operation,mediaType,root,paths,parts,false);
    }
    public JSONObject uploadSceneAsset(Long workspace,Long user,String operation,MultipartFile file) throws Exception {
        return upload(workspace,user,operation,"MODEL","model.glb",List.of("model.glb"),List.of(file),true);
    }
    private JSONObject upload(Long workspace,Long user,String operation,String mediaType,String root,List<String> paths,List<MultipartFile> parts,boolean sceneAsset) throws Exception {
        CanvasPublicationPort port=ports.getIfAvailable();if(port==null)throw new ServiceException("模型资源登记未启用");port.requireBundleEditor(workspace,user);
        if(!uploadSlots.tryAcquire())throw new ServiceException("模型资源准备繁忙，请稍后使用原operationId重试");
        try{return uploadBounded(port,workspace,user,operation,mediaType,root,paths,parts,sceneAsset);}finally{uploadSlots.release();}
    }
    private JSONObject uploadBounded(CanvasPublicationPort port,Long workspace,Long user,String operation,String mediaType,String root,List<String> paths,List<MultipartFile> parts,boolean sceneAsset) throws Exception {
        if(operation==null||!operation.matches("[A-Za-z0-9_-]{1,64}")||!Set.of("MODEL","SCENE_PACKAGE").contains(mediaType)||paths==null||parts==null||paths.size()!=parts.size()||parts.size()>256)throw new ServiceException("模型上传参数无效");
        Map<String,byte[]> input=new TreeMap<>();long total=0;
        for(int i=0;i<parts.size();i++){MultipartFile f=parts.get(i);if(f==null||f.isEmpty()||f.getSize()>67108864)throw new ServiceException("模型文件为空或超过64MiB");total+=f.getSize();if(total>268435456)throw new ServiceException("模型包总量超过256MiB");if(input.putIfAbsent(paths.get(i),f.getBytes())!=null)throw new ServiceException("模型路径重复");}
        CanvasModelBundleInspector.Bundle bundle=inspector.inspect(root,input);
        JSONObject catalog = sceneAsset ? CanvasSceneGlbCatalog.fromDocument(CanvasModelBundleInspector.glb(input.get(root))) : null;
        if (sceneAsset && catalog == null) throw new ServiceException("GLB 内部对象超过预演编辑限制");
        if("SCENE_PACKAGE".equals(mediaType)&&!Set.of("glb","gltf").contains(bundle.format()))throw new ServiceException("场景包必须为GLB/GLTF");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update((mediaType+":"+root).getBytes(StandardCharsets.UTF_8));
        if (sceneAsset) digest.update((byte)1);
        input.forEach((path,bytes)->{byte[] name=path.getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(name.length).array());digest.update(name);digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.length).array());digest.update(bytes);});
        String hash=HexFormat.of().formatHex(digest.digest());JSONObject old=port.existingBundle(workspace,user,operation,hash);if(old!=null)return old;
        String scope=UUID.randomUUID().toString().replace("-","");
        try {
            JSONArray files=new JSONArray();
            for(var entry:bundle.files().entrySet()){
                String ext=CanvasModelBundleInspector.extension(entry.getKey()),location=ledger.upload(scope,entry.getValue(),ext),full=urls.toFullUrl(location);
                if(full==null||!urls.isSiteMediaUrl(full))throw new ServiceException("模型存储地址未受信任");
                files.add(JSONObject.of("path",entry.getKey(),"storageUrl",full,"fileSize",entry.getValue().length,"contentHash",cn.hutool.crypto.digest.DigestUtil.sha256Hex(entry.getValue()),"mimeType",mime(ext)));
                ledger.renew(scope);
            }
            JSONObject manifest=JSONObject.of("format",bundle.format(),"rootPath",bundle.rootPath(),"mediaType",mediaType,"files",files,"references",bundle.references());
            if (catalog != null) manifest.put("glbScene", catalog);
            return new TransactionTemplate(transactions).execute(status->{JSONObject result=port.registerBundle(workspace,user,operation,hash,manifest);if(!result.getBooleanValue("replayed"))ledger.attach(scope,null,result.getLong("projectId"));return result;});
        } finally {ledger.abandon(scope);}
    }
    private static String mime(String ext){return switch(ext){case "glb"->"model/gltf-binary";case "gltf"->"model/gltf+json";case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";case "webp"->"image/webp";case "gif"->"image/gif";case "obj","mtl"->"text/plain";default->"application/octet-stream";};}
}
