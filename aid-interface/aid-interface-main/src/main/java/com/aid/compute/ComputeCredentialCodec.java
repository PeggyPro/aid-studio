package com.aid.compute;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** At-rest worker credentials; independent of HTTP response encryption settings. */
@Component
public final class ComputeCredentialCodec {
    private final byte[] key;
    private final SecureRandom random=new SecureRandom();
    public ComputeCredentialCodec(@Value("${aid.compute.credential-key:${token.secret:}}")String secret){
        try{key=secret==null||secret.length()<32?null:MessageDigest.getInstance("SHA-256").digest(("aid.compute.credentials/v1\0"+secret).getBytes(StandardCharsets.UTF_8));}
        catch(GeneralSecurityException unavailable){throw new IllegalStateException("计算凭证加密组件不可用");}
    }
    public String encrypt(String workerId,String plain){
        requireKey();
        try{byte[] nonce=new byte[12];random.nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(workerId.getBytes(StandardCharsets.UTF_8));
            byte[] data=cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)),payload=Arrays.copyOf(nonce,nonce.length+data.length);
            System.arraycopy(data,0,payload,nonce.length,data.length);return "compute:v1:"+Base64.getEncoder().encodeToString(payload);
        }catch(GeneralSecurityException unavailable){throw new IllegalStateException("计算凭证保存失败");}
    }
    public String decrypt(String workerId,String encrypted){
        requireKey();
        try{if(encrypted==null||!encrypted.startsWith("compute:v1:"))throw new IllegalArgumentException();
            byte[] data=Base64.getDecoder().decode(encrypted.substring(11));if(data.length<29||data.length>4096)throw new IllegalArgumentException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOf(data,12)));
            cipher.updateAAD(workerId.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(data,12,data.length-12),StandardCharsets.UTF_8);
        }catch(GeneralSecurityException|IllegalArgumentException invalid){throw new IllegalStateException("计算凭证无法读取，请重新验证并保存");}
    }
    private void requireKey(){if(key==null)throw new IllegalStateException("请先初始化计算凭证存储主密钥");}
}
