package com.aid.previz.config;
import com.aid.businessscene.BusinessSceneStorageProvider;
import com.aid.previz.workspace.PrevizStoragePolicy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;
@Component
@ConditionalOnProperty(prefix="aid.previz",name="enabled",havingValue="true")
public class PrevizStoragePolicyProvider implements BusinessSceneStorageProvider {
    private final Environment environment;
    public PrevizStoragePolicyProvider(Environment environment){this.environment=environment;}
    @Override public boolean supports(String scene){return "previz_3dbox".equals(scene);}
    @Override public List<Field> fields(String scene){
        if(!supports(scene))return List.of();var result=new ArrayList<Field>();
        for(var key:PrevizStoragePolicy.Key.values()){
            String name;long fallback;
            switch(key){
                case FILE_COUNT -> {name="当前文件数量";fallback=5000;}
                case FILE_BYTES -> {name="当前文件容量";fallback=536870912;}
                case STORED_BYTES -> {name="历史文件内容容量";fallback=1073741824;}
                case STORED_OBJECTS -> {name="历史内容对象数量";fallback=8192;}
                case DOCUMENT_BYTES -> {name="场景文档历史容量";fallback=536870912;}
                case DOCUMENT_VERSIONS -> {name="场景文档历史版本数量";fallback=4096;}
                case SOURCE_BYTES -> {name="工程源码历史容量";fallback=536870912;}
                case SOURCE_VERSIONS -> {name="工程源码历史版本数量";fallback=4096;}
                case ASSET_BYTES -> {name="项目资产容量";fallback=2147483648L;}
                case FREE_BYTES -> {name="资产磁盘保留空间";fallback=268435456;}
                case PENDING_UPLOADS -> {name="未完成上传数量";fallback=8;}
                case ASSET_COUNT -> {name="项目资产数量";fallback=4096;}
                case UPLOAD_RECORDS -> {name="上传记录数量";fallback=32768;}
                default -> throw new IllegalStateException("存储字段未定义");
            }
            long value=environment.getProperty(key.configKey(),Long.class,fallback);
            result.add(new Field(key.configKey(),name,key.suffix.contains("bytes")?"字节":"个",value,key.minimum(),key.maximum()));
        }
        return List.copyOf(result);
    }
}
