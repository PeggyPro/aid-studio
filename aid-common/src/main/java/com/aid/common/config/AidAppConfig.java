package com.aid.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 读取项目相关配置
 *
 * @author 视觉AID
 */
@Component
@ConfigurationProperties(prefix = "aid")
public class AidAppConfig
{
    /** 项目名称 */
    private String name;

    /** 版本 */
    private String version;

    /** 版权年份 */
    private String copyrightYear;

    /** 上传路径 */
    private static String profile;

    /** 获取地址开关 */
    private static boolean addressEnabled;

    /** 验证码类型 */
    private static String captchaType;

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getVersion()
    {
        return version;
    }

    public void setVersion(String version)
    {
        this.version = version;
    }

    public String getCopyrightYear()
    {
        return copyrightYear;
    }

    public void setCopyrightYear(String copyrightYear)
    {
        this.copyrightYear = copyrightYear;
    }

    private static String privateProfile;

    public static void setPrivateProfile(String value) { privateProfile = value; }

    public static String getPrivateProfile() {
        if (privateProfile != null) return privateProfile;
        java.nio.file.Path path = java.nio.file.Path.of(getProfile()).toAbsolutePath().normalize();
        return path.resolveSibling(path.getFileName() + "-private").toString();
    }

    /** Business scratch files share the private storage volume and never fall back to system temp. */
    public static java.nio.file.Path getTemporaryDirectory() throws java.io.IOException {
        java.nio.file.Path path = java.nio.file.Path.of(getPrivateProfile(), "tmp");
        com.aid.common.storage.LocalStorageMigration.validateRoot(path.toAbsolutePath().normalize().toString());
        return java.nio.file.Files.createDirectories(path);
    }

    public static String getProfile()
    {
        return profile;
    }

    public void setProfile(String profile)
    {
        AidAppConfig.profile = profile;
    }

    public static boolean isAddressEnabled()
    {
        return addressEnabled;
    }

    public void setAddressEnabled(boolean addressEnabled)
    {
        AidAppConfig.addressEnabled = addressEnabled;
    }

    public static String getCaptchaType() {
        return captchaType;
    }

    public void setCaptchaType(String captchaType) {
        AidAppConfig.captchaType = captchaType;
    }

    /**
     * 获取导入上传路径
     */
    public static String getImportPath()
    {
        return getProfile() + "/import";
    }

    /**
     * 获取头像上传路径
     */
    public static String getAvatarPath()
    {
        return getProfile() + "/avatar";
    }

    /**
     * 获取下载路径
     */
    public static String getDownloadPath()
    {
        return getProfile() + "/download/";
    }

    /**
     * 获取上传路径
     */
    public static String getUploadPath()
    {
        return getProfile() + "/upload";
    }
}
