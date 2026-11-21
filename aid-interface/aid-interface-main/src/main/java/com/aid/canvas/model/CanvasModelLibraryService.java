package com.aid.canvas.model;

import com.aid.common.aid.image.ManagedImageIO;

import com.aid.canvas.publication.service.CanvasModelBundleInspector;
import com.aid.canvas.publication.service.CanvasPublicationFiles;
import com.aid.canvas.publication.service.CanvasPublicationPort;
import com.aid.canvas.model.mapper.CanvasModelLibraryMapper;
import com.aid.common.aid.oss.core.OssTemplate;
import com.aid.common.aid.oss.util.MediaUrlResolver;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.*;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

/** 全局模型目录只由后台写入；项目使用时登记受权限保护的固定版本引用。 */
@Service @RequiredArgsConstructor
public class CanvasModelLibraryService {
    private final CanvasModelLibraryMapper mapper;
    private final CanvasModelBundleInspector inspector;
    private final CanvasPublicationFiles files;
    private final MediaUrlResolver urls;
    private final OssTemplate oss;
    private final ObjectProvider<CanvasPublicationPort> ports;
    private final PlatformTransactionManager transactions;
    private final java.util.concurrent.Semaphore uploadSlot=new java.util.concurrent.Semaphore(1);
    @org.springframework.beans.factory.annotation.Value("${canvas.models.max-file-bytes:20971520}") private long maxFileBytes;

    public List<Map<String,Object>> categories(boolean publicOnly) {
        return mapper.categories().stream().filter(c->!publicOnly||"ACTIVE".equals(c.get("status"))).toList();
    }
    public List<CanvasLibraryModel> list(CanvasModelLibraryRequests.Query q,boolean publicOnly) {
        if(q.getPageNum()>10000)throw new ServiceException("页码超过范围");
        List<CanvasLibraryModel> result=mapper.list(publicOnly,q.getCategoryId(),q.getKeyword(),q.getPageSize(),(q.getPageNum()-1)*q.getPageSize());
        result.forEach(row->row.setThumbnailUrl(oss.getSignedUrl(row.getThumbnailPath(),900)));return result;
    }
    public CanvasLibraryModel detail(String id) {
        CanvasLibraryModel row=mapper.detail(id);
        if(row==null||"DELETED".equals(row.getStatus()))throw new ServiceException("模型不存在");
        return row;
    }
    public JSONObject preview(String id,boolean publicOnly) {
        CanvasLibraryModel row=detail(id);
        if(publicOnly&&(!"PUBLISHED".equals(row.getStatus())||mapper.activeCategory(row.getCategoryId())==0))throw new ServiceException("模型或分类当前不可新增使用");
        return JSONObject.of("id",row.getId(),"fileUrl",oss.getSignedUrl(row.getStoragePath(),900),
                "thumbnailUrl",oss.getSignedUrl(row.getThumbnailPath(),900),"metadata",JSON.parseObject(row.getMetadataJson()));
    }
    public String category(CanvasModelLibraryRequests.Category request) {
        return new TransactionTemplate(transactions).execute(status->{
            String id=request.getId();
            if(id==null||id.isBlank()){id=uuid();mapper.insertCategory(id,request.getName().trim(),request.getStatus(),request.getSortOrder());}
            else {if(mapper.lockCategory(id)==null)throw new ServiceException("分类不存在");
                if("DELETED".equals(request.getStatus())&&mapper.categoryReferences(id)>0)throw new ServiceException("分类仍被模型引用，请先调整模型分类");
                mapper.updateCategory(id,request.getName().trim(),request.getStatus(),request.getSortOrder());}
            return id;
        });
    }
    public CanvasLibraryModel edit(CanvasModelLibraryRequests.Edit request) {
        return new TransactionTemplate(transactions).execute(status->{
            Map<String,Object> category=mapper.lockCategory(request.getCategoryId());
            if(category==null||"DELETED".equals(category.get("status")))throw new ServiceException("分类不存在");
            CanvasLibraryModel row=mapper.lock(request.getId());
            if(row==null||"DELETED".equals(row.getStatus()))throw new ServiceException("模型不存在");
            if(!Objects.equals(row.getRevision(),request.getRevision()))throw new ServiceException("模型已被修改，请重新读取");
            if("DELETED".equals(request.getStatus())&&mapper.references(row.getId())>0)throw new ServiceException("该版本已被项目引用，只能停用，不能删除");
            String effectiveStatus=request.getStatus()==null?row.getStatus():request.getStatus();
            if("PUBLISHED".equals(effectiveStatus)&&!"ACTIVE".equals(category.get("status")))throw new ServiceException("分类停用期间不能发布模型");
            row.setName(request.getName().trim());row.setCategoryId(request.getCategoryId());row.setTags(Objects.toString(request.getTags(),""));
            if(request.getStatus()!=null)row.setStatus(request.getStatus());
            if(mapper.update(row)!=1)throw new ServiceException("模型版本冲突，请重新读取");return mapper.detail(row.getId());
        });
    }
    public CanvasLibraryModel upload(String name,String category,String modelId,MultipartFile file,MultipartFile thumbnail) throws Exception {
        if(name==null||name.isBlank()||name.length()>100||category==null||!category.matches("[a-f0-9]{32}")
            ||(modelId!=null&&!modelId.isBlank()&&!modelId.matches("[a-f0-9]{32}"))
            ||file==null||file.isEmpty()||file.getSize()>maxFileBytes)throw new ServiceException("请提供名称、分类及大小合规的GLB文件");
        if(!uploadSlot.tryAcquire())throw new ServiceException("模型处理繁忙，请稍后重试");
        String scope=uuid();
        try {
            byte[] bytes=file.getBytes();inspector.inspect("model.glb",Map.of("model.glb",bytes));
            JSONObject geometry=CanvasGlbMetadata.read(bytes);
            if(thumbnail==null||thumbnail.isEmpty()||thumbnail.getSize()>2097152)throw new ServiceException("模型预览图尚未生成，请等待预览完成");
            byte[] image=thumbnail.getBytes();validateThumbnail(image);
            String path=relative(files.upload(scope,bytes,"glb")),thumb=relative(files.upload(scope,image,"png"));
            return new TransactionTemplate(transactions).execute(status->{
                Map<String,Object> c=mapper.lockCategory(category);
                if(c==null||!"ACTIVE".equals(c.get("status")))throw new ServiceException("请选择已启用分类");
                CanvasLibraryModel previous=modelId==null||modelId.isBlank()?null:mapper.latest(modelId);
                if(modelId!=null&&!modelId.isBlank()&&previous==null)throw new ServiceException("待替换模型不存在");
                CanvasLibraryModel row=new CanvasLibraryModel();row.setId(uuid());row.setModelId(previous==null?uuid():previous.getModelId());
                row.setVersionNo(previous==null?1:previous.getVersionNo()+1);row.setName(name.trim());row.setCategoryId(category);
                row.setTags(name.trim()+","+c.get("name"));row.setStatus("DRAFT");row.setStoragePath(path);row.setThumbnailPath(thumb);
                row.setFileSize((long)bytes.length);row.setContentHash(DigestUtil.sha256Hex(bytes));row.setMetadataJson(geometry.toJSONString());
                mapper.insert(row);files.attach(scope,null,null);return detail(row.getId());
            });
        } finally {try {files.abandon(scope);}finally {uploadSlot.release();}}
    }
    public JSONObject use(CanvasModelLibraryRequests.Use request,Long user) {
        CanvasPublicationPort port=ports.getIfAvailable();if(port==null)throw new ServiceException("项目模型登记暂不可用");
        port.requireBundleEditor(request.getWorkspaceId(),user);
        if((request.getId()==null)==(request.getContentHash()==null))throw new ServiceException("请选择模型版本或已登记文件，不能同时指定两者");
        return new TransactionTemplate(transactions).execute(status->{
            String hash=DigestUtil.sha256Hex("library:"+Objects.toString(request.getId(),request.getContentHash()));
            JSONObject previous=port.existingBundle(request.getWorkspaceId(),user,request.getOperationId(),hash);
            if(previous!=null)return previous;
            CanvasLibraryModel selected=request.getId()==null?mapper.byHash(request.getContentHash()):mapper.detail(request.getId());
            if(selected==null)throw new ServiceException("该模型尚未由后台登记，请联系管理员导入历史模型");
            Map<String,Object> category=mapper.lockCategory(selected.getCategoryId());
            CanvasLibraryModel row=mapper.lock(selected.getId());
            if(row==null||!"PUBLISHED".equals(row.getStatus()))throw new ServiceException("模型已停用或不存在，请刷新模型列表");
            if(!Objects.equals(row.getCategoryId(),selected.getCategoryId()))throw new ServiceException("模型分类已变化，请重新选择");
            if(category==null||!"ACTIVE".equals(category.get("status")))throw new ServiceException("模型分类已停用");
            JSONObject manifest=JSONObject.of("format","glb","rootPath","model.glb","mediaType","MODEL","references",new JSONArray(),
                    "files",JSONArray.of(JSONObject.of("path","model.glb","storageUrl",urls.toFullUrl(row.getStoragePath()),"fileSize",row.getFileSize(),"contentHash",row.getContentHash(),"mimeType","model/gltf-binary")));
            JSONObject result=port.registerBundle(request.getWorkspaceId(),user,request.getOperationId(),hash,manifest);
            mapper.use(row.getId(),request.getWorkspaceId(),user,request.getOperationId());return result;
        });
    }
    public JSONArray readResources(CanvasModelLibraryRequests.Resources request,Long user){
        CanvasPublicationPort port=ports.getIfAvailable();if(port==null)throw new ServiceException("模型资源读取暂不可用");
        return port.readBundles(request.getWorkspaceId(),user,request.getResourceIds());
    }
    private String relative(String path){String result=urls.toRelativePath(path);if(result==null||result.contains("://"))throw new ServiceException("模型存储必须属于系统存储并使用相对路径");return result;}
    private static void validateThumbnail(byte[] bytes) throws java.io.IOException {
        byte[] signature={(byte)137,80,78,71,13,10,26,10};
        if(bytes.length<8||!Arrays.equals(signature,Arrays.copyOf(bytes,8)))throw new ServiceException("预览图必须为PNG");
        try(var input=ManagedImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))){
            var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw new ServiceException("预览图无法解析");
            var reader=readers.next();try{reader.setInput(input);if(reader.getWidth(0)>2048||reader.getHeight(0)>2048)throw new ServiceException("预览图尺寸过大");reader.read(0);}finally{reader.dispose();}
        }
    }
    private static String uuid(){return UUID.randomUUID().toString().replace("-","");}
}
