package com.aid.aid.service;

import com.aid.aid.domain.AidConfig;
import com.aid.aid.mapper.AidConfigMapper;
import com.aid.common.aid.core.service.ConfigService;
import com.aid.common.exception.ServiceException;
import com.aid.common.storage.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class LocalStorageRootService {
    private final ConfigService configs;
    private final AidConfigMapper mapper;
    private final LocalStorageBootstrap runtime;
    public Map<String,Object> detail(){
        Map<String,Object> view=new LinkedHashMap<>();String root=value("root"),previous=value("previous_root");
        view.put("configuredRoot",root);view.put("activeRoot",runtime.activeRoot()==null?null:runtime.activeRoot().toString());
        view.put("publicRoot",runtime.publicRoot().toString());view.put("privateRoot",com.aid.common.config.AidAppConfig.getPrivateProfile());
        view.put("restartRequired",!root.isBlank()&&!root.equals(Objects.toString(runtime.activeRoot(),"")));
        view.put("configurationDigest",digest(root+"\n"+previous));view.put("migratedFiles",runtime.copiedFiles());return view;
    }
    @Transactional(rollbackFor=Exception.class)
    public synchronized Map<String,Object> save(String root,String expectedDigest,String actor){
        mapper.selectList(new LambdaQueryWrapper<AidConfig>().eq(AidConfig::getCategory,"local_storage").last("FOR UPDATE"));
        if(!Objects.equals(detail().get("configurationDigest"),expectedDigest))throw new ServiceException("目录配置已变化，请刷新后重试",409);
        try {
            Path target=LocalStorageMigration.validateRoot(root);
            if(target.equals(runtime.activeRoot()))throw new ServiceException("该目录已经生效");
            LocalStorageMigration.preflight(target,runtime.sourceRoots());
            put("previous_root",Objects.toString(runtime.activeRoot(),""),actor);put("root",target.toString(),actor);
            return detail();
        } catch(java.io.IOException invalid){throw new ServiceException(invalid.getMessage());}
    }
    private String value(String key){return Objects.toString(configs.getConfigValue("local_storage",key),"");}
    private void put(String key,String value,String actor){
        AidConfig row=mapper.selectOne(new LambdaQueryWrapper<AidConfig>().eq(AidConfig::getCategory,"local_storage").eq(AidConfig::getConfigName,key).eq(AidConfig::getDelFlag,"0").orderByDesc(AidConfig::getId).last("LIMIT 1"));
        boolean insert=row==null;if(insert){row=new AidConfig();row.setCategory("local_storage");row.setConfigName(key);row.setDelFlag("0");row.setCreateBy(actor);row.setCreateTime(new Date());}
        row.setConfigValue(value);row.setConfigDict("统一本地存储目录；仅专用配置入口维护，重启生效");row.setUpdateBy(actor);row.setUpdateTime(new Date());
        if(insert)mapper.insert(row);else mapper.updateById(row);
    }
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
